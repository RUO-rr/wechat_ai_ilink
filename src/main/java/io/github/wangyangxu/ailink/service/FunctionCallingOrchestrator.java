package io.github.wangyangxu.ailink.service;

import io.github.wangyangxu.ailink.client.LlmClient;
import io.github.wangyangxu.ailink.tool.ToolRegistry;
import io.github.wangyangxu.ailink.tool.ToolRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Function Calling 编排引擎 —— 纯粹的 FC 循环逻辑，与对话历史、文件发送、意图检测无耦合。
 * 内部通过 ToolRouter 动态装配工具子集，降低 LLM 选择负担。
 * <p>
 * 职责：给定消息列表，迭代调用 LLM（每轮带 tools），执行工具，收集 file_path，
 * 直到 LLM 输出纯文本或达到步数上限。
 */
@Service
public class FunctionCallingOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(FunctionCallingOrchestrator.class);
    private static final int MAX_STEPS = 15;

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final ToolRouter toolRouter;
    private final MetricsService metricsService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${llm.base-url}")
    private String baseUrl;

    @Value("${llm.model}")
    private String model;

    @Value("${llm.max-tokens:2048}")
    private int maxTokens;

    @Value("${llm.temperature:0.7}")
    private double temperature;

    @Value("${llm.api-key}")
    private String apiKey;

    public FunctionCallingOrchestrator(LlmClient llmClient, ToolRegistry toolRegistry,
                                        ToolRouter toolRouter, MetricsService metricsService) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.toolRouter = toolRouter;
        this.metricsService = metricsService;
    }

    /** FC 循环执行结果 */
    public record Result(String assistantContent,
                         java.util.LinkedHashSet<String> generatedFiles,
                         java.util.Set<String> explicitlySentFiles) {}

    /**
     * 执行 Function Calling 循环。
     *
     * @param messages 包含系统提示、历史、当前用户消息的消息列表（会被修改：追加 tool_calls 和 tool 结果）
     * @return 最终回复文本 + 生成文件的路径集合
     */
    public Result execute(List<Map<String, Object>> messages, String botId, String userId) {
        // BotContext 由调用方（MainController）设置和清理，本方法只读不写
        return doExecute(messages);
    }

    private Result doExecute(List<Map<String, Object>> messages) {
        // 清理历史中残留的非法 tool 消息对（trim 可能拆散 assistant(tool_calls)→tool 组）
        sanitizeToolMessages(messages);

        // 从消息列表中提取最后一条用户消息，用于领域路由
        String userMessage = extractUserMessage(messages);

        // 动态装配：按领域筛选工具子集
        ToolRouter.RouteResult route = toolRouter.route(userMessage);
        List<Map<String, Object>> toolsDef = route.tools();

        // 领域准则/降级提示注入到 system 消息中
        if (route.domainPrompt() != null) {
            injectDomainPrompt(messages, route.domainPrompt());
        }

        String assistantContent = null;
        java.util.LinkedHashSet<String> generatedFiles = new java.util.LinkedHashSet<>();
        java.util.Set<String> explicitlySentFiles = new java.util.HashSet<>();

        // 跟踪已调用的工具名称（用于 Watcher 后处理）
        java.util.Set<String> calledTools = new java.util.HashSet<>();

        // 循环保护：同一工具+参数连续成功 2 次视为死循环，强制终止
        Map<String, Integer> toolCallTracker = new HashMap<>();
        final int REPEAT_THRESHOLD = 2;
        boolean forceBreak = false;

        for (int step = 0; step < MAX_STEPS; step++) {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", messages);
            requestBody.put("max_tokens", maxTokens);
            requestBody.put("temperature", temperature);
            requestBody.put("tools", toolsDef);

            JsonNode root;
            try {
                long llmStart = System.nanoTime();
                root = llmClient.callChatApi(baseUrl, apiKey, requestBody);
                long llmMs = (System.nanoTime() - llmStart) / 1_000_000;
                metricsService.recordLlmCall(llmMs);
                metricsService.recordFcRound();
            } catch (Exception e) {
                log.error("调用大模型 API 失败, step={}", step, e);
                // 返回部分结果，让调用方决定处置
                return new Result("【网络错误】调用大模型失败：" + e.getMessage(),
                        generatedFiles, explicitlySentFiles);
            }

            JsonNode msgNode = LlmClient.extractMessage(root);
            JsonNode toolCallsNode = LlmClient.extractToolCalls(msgNode);

            if (toolCallsNode == null || !toolCallsNode.isArray() || toolCallsNode.size() == 0) {
                assistantContent = msgNode.get("content").asText();
                break;
            }

            log.info("FC 第{}轮: tool_calls={}", step + 1, toolCallsNode.size());

            // 追加 assistant(tool_calls) 消息
            Map<String, Object> assistantMsg = new HashMap<>();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", msgNode.has("content") && !msgNode.get("content").isNull()
                    ? msgNode.get("content").asText() : null);
            assistantMsg.put("tool_calls", objectMapper.convertValue(toolCallsNode, List.class));
            messages.add(assistantMsg);

            // 执行工具 + 收集 file_path
            for (JsonNode toolCall : toolCallsNode) {
                String toolCallId = toolCall.get("id").asText();
                JsonNode function = toolCall.get("function");
                String functionName = function.get("name").asText();
                String argumentsStr = function.get("arguments").asText();

                log.info("执行工具: id={}, name={}, args={}", toolCallId, functionName, argumentsStr);
                String toolResult = toolRegistry.execute(functionName, argumentsStr);

                // 记录已调用的工具（Watcher 后处理用）
                calledTools.add(functionName);

                // 循环保护：同一工具+参数连续成功，视为死循环
                String callKey = functionName + ":" + argumentsStr;
                if (toolResult != null && toolResult.contains("\"success\": true")) {
                    int count = toolCallTracker.merge(callKey, 1, Integer::sum);
                    if (count >= REPEAT_THRESHOLD) {
                        log.warn("工具 {} 连续成功 {} 次，触发循环保护，强制终止", callKey, count);
                        assistantContent = "任务已完成。";
                        // 仍需将当前 tool 结果追加到 messages，保持上下文完整
                        messages.add(makeToolMsg(toolCallId, functionName, toolResult));
                        // 设置标记跳出双层循环
                        forceBreak = true;
                        break;
                    }
                } else {
                    // 工具失败 → 只清除该工具的重复计数，不影响其他工具
                    toolCallTracker.remove(callKey);
                }

                collectGeneratedFiles(toolResult, generatedFiles, explicitlySentFiles);

                Map<String, Object> toolMsg = new HashMap<>();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", toolCallId);
                toolMsg.put("name", functionName);
                toolMsg.put("content", toolResult);
                messages.add(toolMsg);
            }
            if (forceBreak) break;  // 循环保护触发，退出外层循环
        }

        if (assistantContent == null) {
            log.warn("FC 达到最大步数 {}，强制终止", MAX_STEPS);
            assistantContent = "任务执行步骤过多，已自动终止，请简化需求后重试。";
        }

        // Watcher 后处理：公司领域深度分析漏调行业新闻时，追加系统提示
        assistantContent = applyWatcher(userMessage, assistantContent, calledTools, route.matchedDomain());

        return new Result(assistantContent, generatedFiles, explicitlySentFiles);
    }

    /**
     * 清理消息列表中不合法的 tool 相关消息。
     * DeepSeek API 要求：role='tool' 的消息必须紧跟在含 tool_calls 的 assistant 消息之后。
     * 历史裁剪（trim）可能拆散这种配对，导致 400 错误。
     */
    private static void sanitizeToolMessages(List<Map<String, Object>> messages) {
        // 第一轮：移除孤立的 tool 消息（前面没有 assistant(tool_calls)）
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            if (!"tool".equals(msg.get("role"))) continue;

            // 向前找紧邻的 assistant(tool_calls)
            boolean hasPreceding = false;
            for (int j = i - 1; j >= 0; j--) {
                Map<String, Object> prev = messages.get(j);
                String prevRole = (String) prev.get("role");
                if ("assistant".equals(prevRole) && prev.containsKey("tool_calls")) {
                    hasPreceding = true;
                    break;
                }
                // 允许前面是连续的 tool 消息（同一组 tool_calls 的多个响应）
                if (!"tool".equals(prevRole)) break;
            }
            if (!hasPreceding) {
                messages.remove(i);
            }
        }

        // 第二轮：移除没有 tool 响应的 assistant(tool_calls) 消息
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            if (!"assistant".equals(msg.get("role")) || !msg.containsKey("tool_calls")) continue;

            // 检查后面是否紧跟 tool 消息
            boolean hasFollowing = (i + 1 < messages.size())
                    && "tool".equals(messages.get(i + 1).get("role"));
            if (!hasFollowing) {
                messages.remove(i);
            }
        }
    }

    /** 从消息列表中提取最后一条 user 消息的文本 */
    private static String extractUserMessage(List<Map<String, Object>> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).get("role"))) {
                Object content = messages.get(i).get("content");
                return content != null ? content.toString() : "";
            }
        }
        return "";
    }

    /**
     * Watcher：检查 LLM 是否遗漏关键工具调用。
     * company 领域下，若用户要求深度分析但缺行业新闻，追加系统提示。
     */
    private String applyWatcher(String userMessage, String llmAnswer,
                                  Set<String> calledTools, String matchedDomain) {
        if (!"company".equals(matchedDomain)) return llmAnswer;
        // 仅当用户消息包含深度分析信号（分析/前景/投资/评估/报告）时才触发
        boolean isDeepAnalysis = userMessage != null && (
                userMessage.contains("分析") || userMessage.contains("前景")
                || userMessage.contains("投资") || userMessage.contains("评估")
                || userMessage.contains("报告") || userMessage.contains("怎么样"));
        if (!isDeepAnalysis) return llmAnswer;

        boolean hasCompanyInfo = calledTools.contains("search_company_info");
        boolean hasIndustryNews = calledTools.contains("search_industry_news");

        if (hasCompanyInfo && !hasIndustryNews) {
            log.warn("深度分析场景下 LLM 漏调行业新闻工具，Watcher 追加提示");
            return llmAnswer
                    + "\n\n---\n> **系统提示**：如需了解该公司的行业政策与未来前景，"
                    + "可继续追问「行业动态」或「发展规划」。";
        }
        return llmAnswer;
    }

    /** 将兜底/领域提示注入到 system 消息中 */
    private static void injectDomainPrompt(List<Map<String, Object>> messages, String prompt) {
        for (Map<String, Object> msg : messages) {
            if ("system".equals(msg.get("role"))) {
                String original = (String) msg.get("content");
                msg.put("content", prompt + "\n\n" + (original != null ? original : ""));
                return;
            }
        }
        // 没有 system 消息 → 插入一条
        Map<String, Object> sysMsg = new HashMap<>();
        sysMsg.put("role", "system");
        sysMsg.put("content", prompt);
        messages.add(0, sysMsg);
    }

    private static Map<String, Object> makeToolMsg(String toolCallId, String name, String content) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", "tool");
        msg.put("tool_call_id", toolCallId);
        msg.put("name", name);
        msg.put("content", content);
        return msg;
    }

    /** 从工具返回的 JSON 中提取 file_path。已由工具内部发送的（含 "sent": true）跳过收集 */
    static void collectGeneratedFiles(String toolResult,
                                       java.util.LinkedHashSet<String> generatedFiles,
                                       java.util.Set<String> explicitlySentFiles) {
        if (toolResult == null || !toolResult.contains("\"success\": true")) return;
        // 工具已内部发送 → 跳过兜底收集
        if (toolResult.contains("\"sent\": true")) return;
        String fp = extractJsonString(toolResult, "file_path");
        if (fp == null) return;
        generatedFiles.add(fp);
        if (toolResult.contains("\"send_document\"")) {
            explicitlySentFiles.add(fp);
        }
    }

    /** 简易 JSON 字符串值提取，不依赖 ObjectMapper */
    static String extractJsonString(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                sb.append(json.charAt(i + 1));
                i++;
                continue;
            }
            if (c == '"') break;
            sb.append(c);
        }
        return sb.toString();
    }
}
