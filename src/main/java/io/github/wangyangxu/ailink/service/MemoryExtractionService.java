package io.github.wangyangxu.ailink.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.wangyangxu.ailink.client.LlmClient;
import io.github.wangyangxu.ailink.mapper.AgentMemoryMapper;
import io.github.wangyangxu.ailink.mapper.ChatMessageMapper;
import io.github.wangyangxu.ailink.model.AgentMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 每轮对话后的记忆提取（异步执行，不阻塞用户回复）。
 * 成本控制：extraction-enabled + extraction-sample-rate 采样。
 * 容错：整包 JSON 解析失败 → 跳过本轮（下轮重试）；单条非法 → 丢弃该条，保留好条目。
 */
@Service
public class MemoryExtractionService {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionService.class);

    @Value("${llm.base-url}")
    private String baseUrl;

    @Value("${llm.model}")
    private String model;

    @Value("${llm.api-key}")
    private String apiKey;

    @Value("${llm.memory.extraction-enabled:true}")
    private boolean enabled;

    @Value("${llm.memory.extraction-sample-rate:1.0}")
    private double sampleRate;

    @Autowired
    private LlmClient llmClient;

    @Autowired
    private MemoryService memoryService;

    @Autowired
    private AgentMemoryMapper memoryMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Random random = new Random();

    public void extract(String botId, String userId, List<Map<String, Object>> snapshot) {
        if (!enabled) return;
        if (sampleRate < 1.0 && random.nextDouble() >= sampleRate) {
            log.debug("记忆提取采样跳过 userId={} sampleRate={}", userId, sampleRate);
            return;
        }
        String userMessage = lastUserMessage(snapshot);
        if (userMessage == null || userMessage.isBlank()) return;

        List<AgentMemory> existing = memoryMapper.findActiveFacts(userId, 20);
        String response;
        try {
            response = callLlm(userMessage, existing);
        } catch (Exception e) {
            log.warn("记忆提取调用失败 userId={}: {}", userId, e.getMessage());
            return;
        }
        if (response == null || response.isBlank()) return;

        List<MemoryService.ExtractedMemory> entries = parse(response);
        if (entries.isEmpty()) {
            log.debug("本轮无可写入的记忆条目 userId={}", userId);
            return;
        }
        Long sourceMessageId = null;
        try {
            sourceMessageId = chatMessageMapper.findLatestId(botId, userId);
        } catch (Exception e) {
            log.warn("记忆溯源查询失败 userId={}，本次记忆无溯源: {}", userId, e.getMessage());
        }
        memoryService.recordExtracted(userId, sourceMessageId, entries);
    }

    private String callLlm(String userMessage, List<AgentMemory> existing) throws Exception {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", buildSystemPrompt(existing)));
        messages.add(Map.of("role", "user", "content", userMessage));

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("max_tokens", 512);
        body.put("temperature", 0);

        JsonNode root = llmClient.callChatApi(baseUrl, apiKey, body);
        JsonNode msgNode = LlmClient.extractMessage(root);
        return LlmClient.extractContentWithReasoningFallback(msgNode);
    }

    private String buildSystemPrompt(List<AgentMemory> existing) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是长期记忆提取器。根据用户最近一条消息，提取可长期生效的用户事实或偏好。\n");
        sb.append("输出严格 JSON 数组，每个元素格式：");
        sb.append("{\"memory_type\":\"fact|preference\",\"dimension\":\"英文小写语义维度\",");
        sb.append("\"content\":\"中文记忆描述\",\"conflict_action\":\"new|supersede|skip\"}\n");
        sb.append("规则：\n");
        sb.append("1. 只提取可长期生效的信息，一次性请求不提取；\n");
        sb.append("2. dimension 用简洁英文小写（如 answer_style、timezone、company_focus），同类偏好必须用同一 dimension；\n");
        sb.append("3. 与现有记忆矛盾时 conflict_action=supersede；重复琐碎时 skip；否则 new；\n");
        sb.append("4. 无内容可提取时返回 []；\n");
        sb.append("5. 只输出 JSON 数组，不要任何解释、代码块标记或尾随逗号。\n");
        if (!existing.isEmpty()) {
            sb.append("\n现有记忆（供冲突判断）：\n");
            for (AgentMemory m : existing) {
                sb.append("- [").append(m.getDimension()).append("] ").append(m.getContent()).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 解析 LLM 输出。整包解析失败 → WARN + 空列表（本轮跳过）；单条字段缺失在 MemoryService 层丢弃。
     */
    List<MemoryService.ExtractedMemory> parse(String content) {
        String json = stripFences(content);
        List<MemoryService.ExtractedMemory> result = new ArrayList<>();
        try {
            JsonNode arr = objectMapper.readTree(json);
            if (!arr.isArray()) {
                log.warn("记忆提取 JSON 非数组，跳过本轮: {}", truncate(json));
                return result;
            }
            for (JsonNode node : arr) {
                try {
                    result.add(new MemoryService.ExtractedMemory(
                            node.path("memory_type").asText(null),
                            node.path("dimension").asText(null),
                            node.path("content").asText(null),
                            node.path("conflict_action").asText(null),
                            node.toString()));
                } catch (Exception e) {
                    log.warn("记忆条目解析失败，丢弃本条: {}", truncate(node.toString()));
                }
            }
        } catch (Exception e) {
            log.warn("记忆提取 JSON 解析失败，跳过本轮: {}", truncate(json));
        }
        return result;
    }

    private static String lastUserMessage(List<Map<String, Object>> snapshot) {
        if (snapshot == null) return null;
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = snapshot.get(i);
            if ("user".equals(msg.get("role")) && msg.get("content") != null) {
                return msg.get("content").toString();
            }
        }
        return null;
    }

    private static String stripFences(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.startsWith("```")) {
            t = t.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "");
        }
        return t.trim();
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
