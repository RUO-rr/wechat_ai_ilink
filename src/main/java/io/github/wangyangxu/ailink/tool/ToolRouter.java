package io.github.wangyangxu.ailink.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 领域工具路由器 —— 基于用户消息的领域信号词匹配，动态筛选工具子集。
 * <p>
 * 正常模式：匹配到单一领域 → 只注入该领域的工具（≤5 个），降低 LLM 选择负担。
 * 降级模式：未匹配到 / 复合意图 → 全量注入 + 兜底提示语。
 */
@Component
public class ToolRouter {

    private static final Logger log = LoggerFactory.getLogger(ToolRouter.class);

    /** 领域 → 信号词映射 */
    private static final Map<String, List<String>> DOMAIN_SIGNALS = Map.of(
            "weather", List.of("天气", "气温", "下雨", "刮风", "晴天", "阴天", "温度", "湿度"),
            "document", List.of("文档", "word", "标题", "缩进", " 段落", "字体", "加粗", "修改", "创建", "生成一份",
                    "excel", "表格", "xlsx", "电子表格", "数据表", "pdf", "PDF", "转pdf", "转PDF",
                    "简历", "resume", "cv"),
            "company", List.of("公司", "企业", "股票", "股价", "行业", "分析", "报告", "上市", "融资", "工商", "查询"),
            "draw", List.of("画图", "生成图片", "绘制", "画一个")
    );

    private final ToolRegistry toolRegistry;
    private final FallbackPromptBuilder fallbackPromptBuilder;

    /** 企业研究类任务执行准则（注入到 company 领域的 system prompt） */
    private static final String COMPANY_RESEARCH_GUIDELINES = """
            【企业研究类任务执行准则】

            当你面对企业研究类任务时，请遵循以下业界通用分析方法：

            1. 信息完整性原则：一份高质量的企业分析报告应包含"工商基本面"和"行业动态/政策面"两个维度。
            2. 工具调用策略：
               - 如果用户仅询问"公司地址/法人"等事实性问题，只需调用 search_company_info。
               - 如果用户要求"分析前景"、"发展规划"、"是否值得投资"，请按序调用 search_company_info（获取基本面）和 search_industry_news（获取政策与动态），最后整合成结构化报告。
            3. 异常处理：如果某个工具返回空数据或报错，请不要中断整个流程。基于已有的部分数据生成结论，并明确告知用户缺失的部分。""";

    public ToolRouter(ToolRegistry toolRegistry, FallbackPromptBuilder fallbackPromptBuilder) {
        this.toolRegistry = toolRegistry;
        this.fallbackPromptBuilder = fallbackPromptBuilder;
    }

    /** 路由结果 */
    public record RouteResult(List<Map<String, Object>> tools,
                               boolean isFallback,
                               String domainPrompt,    // 降级=兜底提示，正常=领域准则
                               String matchedDomain) {} // 命中的领域名

    /**
     * 根据用户消息筛选工具。
     * @param userMessage 用户原始消息文本
     * @return 筛选后的工具定义列表 + 是否降级 + 降级提示
     */
    public RouteResult route(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return fallback("消息为空");
        }

        // 统计各领域信号词命中数
        Map<String, Integer> hits = new LinkedHashMap<>();
        for (var entry : DOMAIN_SIGNALS.entrySet()) {
            int count = 0;
            for (String signal : entry.getValue()) {
                if (userMessage.contains(signal)) count++;
            }
            if (count > 0) hits.put(entry.getKey(), count);
        }

        // 未命中任何领域 → 降级
        if (hits.isEmpty()) {
            return fallback("无领域匹配");
        }

        // 多个领域命中 → 复合意图，降级
        if (hits.size() >= 2) {
            log.info("复合意图检测: 命中领域={}", hits.keySet());
            return fallback("复合意图: " + hits.keySet());
        }

        // 单一领域 → 正常模式
        String domain = hits.keySet().iterator().next();
        List<Map<String, Object>> domainTools = toolRegistry.getAllTools().stream()
                .filter(t -> t.domain().equals(domain) || "general".equals(t.domain()))
                .map(ToolDefinition::getDefinition)
                .collect(Collectors.toList());

        // 领域专属准则
        String domainPrompt = "company".equals(domain) ? COMPANY_RESEARCH_GUIDELINES : null;

        log.info("正常路由: domain={}, tools={}", domain,
                domainTools.stream().map(d -> d.get("function"))
                        .map(f -> ((Map<String, Object>) f).get("name"))
                        .collect(Collectors.toList()));

        return new RouteResult(domainTools, false, domainPrompt, domain);
    }

    private RouteResult fallback(String reason) {
        log.info("降级模式: reason={}, totalTools={}", reason,
                toolRegistry.getAllTools().size());
        return new RouteResult(
                toolRegistry.getAllDefinitions(),
                true,
                fallbackPromptBuilder.build(),
                null
        );
    }

    /**
     * 兜底提示语构建器 —— 告诉 LLM 进入自主决策模式。
     */
    @Component
    static class FallbackPromptBuilder {

        private final ToolRegistry toolRegistry;

        FallbackPromptBuilder(ToolRegistry toolRegistry) {
            this.toolRegistry = toolRegistry;
        }

        String build() {
            String toolsDesc = toolRegistry.getAllTools().stream()
                    .map(t -> {
                        Map<String, Object> fn = (Map<String, Object>) t.getDefinition().get("function");
                        return "- " + fn.get("name") + ": " + fn.get("description");
                    })
                    .collect(Collectors.joining("\n"));

            return "系统未能精确识别您的意图，已进入自主决策模式。\n"
                    + "请根据用户问题，自行判断需要调用哪些工具。如果不确定，可以反问用户澄清。\n\n"
                    + "可用工具：\n" + toolsDesc;
        }
    }
}
