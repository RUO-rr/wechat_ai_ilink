package io.github.wangyangxu.ailink.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * LLM API 调用客户端，封装 OpenAI 兼容接口的 URL 构建、Header 设置、请求发送和响应解析。
 * 消除 ChatTextService / ChatImageService / ChatDrawService 中的重复 HTTP 样板代码。
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LlmClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 调用 OpenAI 兼容的 /v1/chat/completions 接口，返回完整的 JSON 响应。
     *
     * @param baseUrl     API 基础地址
     * @param apiKey      API Key
     * @param requestBody 请求体（model / messages / max_tokens / temperature / tools 等）
     * @return 完整响应的 JsonNode，调用方负责从中提取所需字段
     */
    public JsonNode callChatApi(String baseUrl, String apiKey, Map<String, Object> requestBody) {
        String url = baseUrl.replaceAll("/+$", "") + "/v1/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    url, new HttpEntity<>(requestBody, headers), String.class);
            return objectMapper.readTree(response.getBody());
        } catch (RestClientException e) {
            log.error("调用 LLM API 失败: {}", e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            log.error("解析 LLM 响应失败", e);
            throw new RuntimeException("解析 LLM 响应失败", e);
        }
    }

    /**
     * 从 OpenAI 兼容响应中提取 message 节点。
     */
    public static JsonNode extractMessage(JsonNode response) {
        return response.get("choices").get(0).get("message");
    }

    /**
     * 从 OpenAI 兼容响应中提取 content 文本。
     */
    public static String extractContent(JsonNode messageNode) {
        return messageNode.get("content").asText();
    }

    /**
     * 从 OpenAI 兼容响应中提取 content，如果 content 为空则回退到 reasoning_content。
     * 适用于 DeepSeek 思考模式的响应。
     */
    public static String extractContentWithReasoningFallback(JsonNode messageNode) {
        String content = messageNode.get("content").asText().trim();
        if (content.isEmpty() && messageNode.has("reasoning_content")) {
            content = messageNode.get("reasoning_content").asText().trim();
        }
        return content;
    }

    /**
     * 从 message 节点中提取 tool_calls（可能为 null）。
     */
    public static JsonNode extractToolCalls(JsonNode messageNode) {
        return messageNode.get("tool_calls");
    }
}
