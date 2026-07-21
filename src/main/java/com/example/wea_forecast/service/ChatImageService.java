package com.example.wea_forecast.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.sdk.core.model.ImageItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ChatImageService {

    private static final Logger log = LoggerFactory.getLogger(ChatImageService.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private ConversationHistory history;

    @Autowired
    private IlinkService ilinkService;

    @Value("${llm.vision-base-url}")
    private String visionBaseUrl;

    @Value("${llm.vision-model}")
    private String visionModel;

    @Value("${llm.vision-api-key}")
    private String visionApiKey;

    @Value("${llm.max-tokens:2048}")
    private int maxTokens;

    @Value("${llm.temperature:0.7}")
    private double temperature;

    public String chat(String userId, ImageItem imageItem) {
        if (visionBaseUrl == null || visionBaseUrl.isBlank()) {
            log.error("未配置 llm.vision-base-url");
            return "【系统错误】未配置视觉模型 API 地址";
        }
        if (visionModel == null || visionModel.isBlank()) {
            log.error("未配置 llm.vision-model");
            return "【系统错误】未配置视觉模型名称";
        }
        if (visionApiKey == null || visionApiKey.isBlank()) {
            log.error("未配置 llm.vision-api-key");
            return "【系统错误】未配置视觉模型 API Key";
        }

        // 1. 下载图片并转 Base64
        byte[] imageBytes;
        try {
            imageBytes = ilinkService.downloadMedia(imageItem.getMedia());
        } catch (IOException e) {
            log.error("下载图片失败: {}", e.getMessage(), e);
            return "【错误】图片下载失败，请重试";
        }
        String base64 = Base64.getEncoder().encodeToString(imageBytes);

        // 2. 构建多模态请求（不带历史，单独调视觉模型）
        String apiUrl = visionBaseUrl.replaceAll("/+$", "") + "/v1/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + visionApiKey);

        // content 数组：先文本引导，再图片
        List<Map<String, Object>> contentParts = new ArrayList<>();

        Map<String, Object> textPart = new HashMap<>();
        textPart.put("type", "text");
        textPart.put("text", "请详细描述这张图片里的内容。");
        contentParts.add(textPart);

        Map<String, Object> imagePart = new HashMap<>();
        imagePart.put("type", "image_url");
        Map<String, String> imageUrlObj = new HashMap<>();
        imageUrlObj.put("url", "data:image/jpeg;base64," + base64);
        imagePart.put("image_url", imageUrlObj);
        contentParts.add(imagePart);

        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", contentParts);

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(userMsg);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", visionModel);
        requestBody.put("messages", messages);
        requestBody.put("max_tokens", maxTokens);
        requestBody.put("temperature", temperature);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        // 3. 调用视觉模型
        String responseBody;
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(apiUrl, request, String.class);
            responseBody = response.getBody();
        } catch (RestClientException e) {
            log.error("调用视觉模型失败: {}", e.getMessage(), e);
            return "【网络错误】图片识别失败：" + e.getMessage();
        }

        // 4. 解析响应
        String visionResult;
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            visionResult = root.get("choices").get(0).get("message").get("content").asText();
        } catch (Exception e) {
            log.error("解析视觉模型响应失败, body={}: {}", responseBody, e.getMessage(), e);
            return "【数据错误】解析图片识别结果失败";
        }

        // 5. 存入历史（只存 user 摘要，不存 assistant 回复）
        history.getOrCreate(userId);
        String summary = "[用户发送了一张图片，内容：" + visionResult + "]";
        history.addMessage(userId, "user", summary);
        history.trim(userId);

        log.info("图片识别完成 userId={}", userId);
        return "图片已经收到啦，我可以帮你做些什么呢？";
    }
}
