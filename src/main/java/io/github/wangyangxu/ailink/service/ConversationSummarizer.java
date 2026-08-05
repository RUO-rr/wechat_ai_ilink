package io.github.wangyangxu.ailink.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.wangyangxu.ailink.client.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 滚动摘要 —— 每 N 轮对话触发一次轻量摘要，作为"窗口压缩"的次要记忆手段。
 * 最新摘要取代旧摘要（supersedes_id 链保留前身），读路径只注入最新一条。
 */
@Service
public class ConversationSummarizer {

    private static final Logger log = LoggerFactory.getLogger(ConversationSummarizer.class);

    /** 参与摘要的消息窗口（最近 N 条，不含 system） */
    private static final int WINDOW_SIZE = 20;

    @Value("${llm.base-url}")
    private String baseUrl;

    @Value("${llm.model}")
    private String model;

    @Value("${llm.api-key}")
    private String apiKey;

    @Autowired
    private LlmClient llmClient;

    @Autowired
    private MemoryService memoryService;

    public void summarize(String userId, List<Map<String, Object>> snapshot) {
        List<String> window = recentMessages(snapshot);
        if (window.isEmpty()) return;

        try {
            String summary = callLlm(window);
            if (summary != null && !summary.isBlank()) {
                memoryService.recordSummary(userId, summary);
                log.info("滚动摘要已生成并落库: userId={} 窗口={} 条", userId, window.size());
            }
        } catch (Exception e) {
            log.warn("滚动摘要生成失败 userId={}: {}", userId, e.getMessage());
        }
    }

    private String callLlm(List<String> window) throws Exception {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content",
                "将以下对话压缩成一段简短摘要（100-200 字），保留关键事实、决定和用户偏好信号。只输出摘要文本，不要解释。"));
        messages.add(Map.of("role", "user", "content", String.join("\n", window)));

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("max_tokens", 512);
        body.put("temperature", 0.3);

        JsonNode root = llmClient.callChatApi(baseUrl, apiKey, body);
        JsonNode msgNode = LlmClient.extractMessage(root);
        return LlmClient.extractContentWithReasoningFallback(msgNode);
    }

    private static List<String> recentMessages(List<Map<String, Object>> snapshot) {
        List<String> result = new ArrayList<>();
        if (snapshot == null) return result;
        for (Map<String, Object> msg : snapshot) {
            Object role = msg.get("role");
            if ("system".equals(role)) continue;
            Object content = msg.get("content");
            if (content == null || content.toString().isBlank()) continue;
            result.add(role + ": " + content);
        }
        if (result.size() > WINDOW_SIZE) {
            return new ArrayList<>(result.subList(result.size() - WINDOW_SIZE, result.size()));
        }
        return result;
    }
}
