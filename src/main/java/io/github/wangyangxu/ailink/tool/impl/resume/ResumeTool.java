package io.github.wangyangxu.ailink.tool.impl.resume;

import io.github.wangyangxu.ailink.service.IintService;
import io.github.wangyangxu.ailink.tool.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 简历生成工具 —— Function Calling 瘦壳路由。
 * <p>
 * 对外暴露一个 "resume" 工具名，内部按 operation 字段分发到各 ResumeOperation 实现。
 * 参考 resume-builder-cn skill（resources/resume-builder/）的证据驱动简历生成方法论。
 * <p>
 * 主要操作：
 * <ul>
 *   <li>{@code generate_resume} — 从结构化数据生成 Word 简历文档</li>
 *   <li>{@code generate_html} — 生成 HTML 格式简历（ATS 友好或可编辑）</li>
 *   <li>{@code generate_markdown} — 生成 Markdown 格式简历</li>
 *   <li>{@code list_templates} — 列出可用的简历模板</li>
 * </ul>
 * <p>
 * 新增操作只需新建 ResumeOperation 实现类并标注 @Component，本类零修改。
 */
@Component
public class ResumeTool implements ToolDefinition {

    private static final Logger log = LoggerFactory.getLogger(ResumeTool.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String TOOL_NAME = "resume";
    private static final String DEFAULT_OUTPUT_DIR = "data/resumes";

    @Value("${resume.output-dir:" + DEFAULT_OUTPUT_DIR + "}")
    private String outputDir;

    private final IintService iintService;

    /** 操作名 → 操作实例 的路由表 */
    private final Map<String, ResumeOperation> operations;

    /**
     * Spring 自动注入所有 ResumeOperation 实现类，构建路由表。
     */
    public ResumeTool(List<ResumeOperation> ops, IintService iintService) {
        Map<String, ResumeOperation> map = new LinkedHashMap<>();
        for (ResumeOperation op : ops) {
            map.put(op.name(), op);
        }
        this.operations = Collections.unmodifiableMap(map);
        this.iintService = iintService;
        log.info("ResumeTool 已注册 {} 个操作: {}", operations.size(),
                operations.keySet().stream().collect(Collectors.joining(", ")));
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String domain() {
        return "document";
    }

    @Override
    public Map<String, Object> getDefinition() {
        // 动态聚合所有操作的 enum 和 description
        List<String> opNames = new ArrayList<>(operations.keySet());
        String opDescription = operations.values().stream()
                .map(ResumeOperation::description)
                .collect(Collectors.joining("；"));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("operation", Map.of(
                "type", "string",
                "enum", opNames,
                "description", "操作类型：" + opDescription
        ));

        // 各操作独有参数
        for (ResumeOperation op : operations.values()) {
            Map<String, Object> opParams = op.parameters();
            if (opParams != null) {
                for (Map.Entry<String, Object> entry : opParams.entrySet()) {
                    properties.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
        }

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of("operation"));

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", TOOL_NAME);
        function.put("description", "简历生成与管理工具。支持从结构化信息生成 Word/HTML/Markdown 格式的简历。"
                + "当用户要求生成简历时调用：先用对话收集个人信息，再用 generate_resume 生成简历文件。"
                + "详细的简历编写规则和模板位于 resources/resume-builder/ 目录下。");
        function.put("parameters", parameters);

        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("type", "function");
        definition.put("function", function);
        return definition;
    }

    @Override
    public String execute(String argumentsJson) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            String operation = args.has("operation") ? args.get("operation").asText() : "";

            if (operation.isBlank()) {
                String available = String.join("、", operations.keySet());
                return "{\"error\": \"请指定操作类型（operation）：" + available + "\"}";
            }

            ResumeOperation op = operations.get(operation.trim());
            if (op == null) {
                return "{\"error\": \"不支持的操作: " + operation + "，可用操作: "
                        + String.join("、", operations.keySet()) + "\"}";
            }

            return op.execute(args, outputDir, iintService);

        } catch (Exception e) {
            log.error("简历工具执行失败", e);
            return "{\"error\": \"简历操作异常: " + e.getMessage() + "\"}";
        }
    }
}
