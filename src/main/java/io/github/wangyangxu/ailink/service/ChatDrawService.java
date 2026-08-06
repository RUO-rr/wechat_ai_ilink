package io.github.wangyangxu.ailink.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ChatDrawService {

    private static final Logger log = LoggerFactory.getLogger(ChatDrawService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private ConversationHistory history;

    @Autowired
    private IintService iintService;

    public ChatDrawService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Value("${llm.base-url}")
    private String textBaseUrl;

    @Value("${llm.model}")
    private String textModel;

    @Value("${llm.api-key}")
    private String textApiKey;

    @Value("${llm.draw-base-url}")
    private String drawBaseUrl;

    @Value("${llm.draw-model}")
    private String drawModel;

    @Value("${llm.draw-api-key}")
    private String drawApiKey;

    /**
     * 文生图全流程：等待提示 → 存用户消息 → 优化prompt → 调绘图API → 发图片 → 存摘要。
     * 消息发送由本方法自行处理，成功返回 null（MainController 无需再发文字回复）；
     * 失败抛异常，由调用方提示原因并回退文本回复。
     */
    public String draw(String userId, String userMessage) {
        // 1. 发送等待提示
        try {
            iintService.sendText(BotContext.currentBotId(), userId, "正在生成您的图片，请稍候...");
        } catch (Exception e) {
            log.error("发送等待提示失败: {}", e.getMessage());
        }

        // 2. 用户消息存入历史
        history.getOrCreate(userId);
        history.addMessage(userId, "user", userMessage);

        // 3. 调文本模型优化提示词
        String optimizedPrompt = optimizePrompt(userId, userMessage);
        if (optimizedPrompt == null) {
            throw new RuntimeException("提示词优化失败");
        }
        log.info("优化后的提示词: {}", optimizedPrompt);

        // 4. 调用绘图 API
        byte[] imageBytes;
        try {
            imageBytes = callDrawApi(optimizedPrompt);
        } catch (Exception e) {
            log.error("调用绘图 API 失败: {}", e.getMessage(), e);
            throw new RuntimeException("图片生成失败: " + e.getMessage(), e);
        }

        // 5. 发送图片
        try {
            iintService.sendImage(BotContext.currentBotId(), userId, imageBytes, "generated.png", null);
        } catch (Exception e) {
            log.error("发送图片失败: {}", e.getMessage(), e);
            throw new RuntimeException("图片发送失败: " + e.getMessage(), e);
        }

        // 6. 存入历史摘要（不入优化后 prompt）
        history.addMessage(userId, "assistant", "[已发送图片，提示词：" + optimizedPrompt + "]");
        history.trim(userId);

        log.info("文生图完成 userId={}", userId);
        return null;
    }

    /**
     * 带上完整对话上下文，调文本模型优化图片生成提示词。
     * 不修改历史记录。
     */
    private String optimizePrompt(String userId, String userMessage) {
        if (textBaseUrl == null || textBaseUrl.isBlank()
                || textModel == null || textModel.isBlank()
                || textApiKey == null || textApiKey.isBlank()) {
            log.error("文本模型未配置，无法优化提示词");
            return null;
        }

        String apiUrl = textBaseUrl.replaceAll("/+$", "") + "/v1/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + textApiKey);

        // 取历史快照 + 追加优化指令
        List<Map<String, Object>> messages = new ArrayList<>(history.getSnapshot(userId));

        Map<String, Object> instruction = new HashMap<>();
        instruction.put("role", "user");
        instruction.put("content",
                "根据以下对话上下文，帮我提取并优化一张图片生成提示词（只返回提示词，不要解释）。用户要求：" + userMessage);
        messages.add(instruction);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", textModel);
        requestBody.put("messages", messages);
        requestBody.put("max_tokens", 512);
        requestBody.put("temperature", 0.7);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    apiUrl, new HttpEntity<>(requestBody, headers), String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            log.debug("优化提示词: {}", root.get("choices").get(0).get("message").get("content").asText().trim());
            return root.get("choices").get(0).get("message").get("content").asText().trim();
        } catch (Exception e) {
            log.error("优化提示词失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 调阿里百炼 DashScope 文生图 API (qwen-image-2.0)，使用标准同步 multimodal-generation 接口。
     */
    private byte[] callDrawApi(String prompt) throws Exception {
        String apiUrl = drawBaseUrl.replaceAll("/+$", "")
                + "/api/v1/services/aigc/multimodal-generation/generation";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + drawApiKey);

        // 1. 构建 messages 标准结构
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", drawModel);

        // content 数组里只放 text
        List<Map<String, String>> content = new ArrayList<>();
        Map<String, String> textPart = new HashMap<>();
        textPart.put("text", prompt);
        content.add(textPart);

        // 单条 user 消息
        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", content);

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(userMsg);

        Map<String, Object> input = new HashMap<>();
        input.put("messages", messages);
        requestBody.put("input", input);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("size", "1024*1024");
        parameters.put("n", 1);
        requestBody.put("parameters", parameters);

        // 2. 转换为 JSON 字符串发送
        String jsonToSend = objectMapper.writeValueAsString(requestBody);

        log.debug("请求生图 API 的完整地址: {}", apiUrl);
        log.debug("发送给生图 API 的请求体: {}", jsonToSend);

        HttpEntity<String> request = new HttpEntity<>(jsonToSend, headers);

        // 3. 发送请求
        ResponseEntity<String> response = restTemplate.postForEntity(apiUrl, request, String.class);
        String responseBody = response.getBody();
        log.debug("生图 API 原始响应: {}", responseBody);

        // 4. 解析响应
        JsonNode root = objectMapper.readTree(responseBody);

        // 先检查是否有错误响应
        if (root.has("code") && root.has("message")) {
            String errCode = root.get("code").asText();
            String errMsg = root.get("message").asText();
            throw new RuntimeException("生图 API 返回错误 [" + errCode + "]: " + errMsg);
        }

        String imageUrl = null;

        // 兼容两种响应格式：output.results[0].url（旧版）/ output.choices[0].message.content[0].image（新版）
        JsonNode output = root.path("output");
        JsonNode results = output.path("results");
        if (results.isArray() && results.size() > 0) {
            imageUrl = results.get(0).path("url").asText();
        }
        if ((imageUrl == null || imageUrl.isBlank()) && output.has("choices")) {
            JsonNode choices = output.get("choices");
            if (choices.isArray() && choices.size() > 0) {
                JsonNode contentArr = choices.get(0).path("message").path("content");
                if (contentArr.isArray() && contentArr.size() > 0) {
                    imageUrl = contentArr.get(0).path("image").asText();
                }
            }
        }

        if (imageUrl == null || imageUrl.isBlank()) {
            throw new RuntimeException("生图 API 返回的结果为空，原始响应: " + responseBody);
        }
        log.debug("生图成功，图片链接: {}", imageUrl);

        // 5. 下载图片转为 byte[]（用 URI 避免签名 URL 被二次编码）
        return restTemplate.getForObject(URI.create(imageUrl), byte[].class);
    }

}
