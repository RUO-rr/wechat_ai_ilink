package io.github.wangyangxu.ailink.tool.impl;

import io.github.wangyangxu.ailink.tool.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;

/**
 * AI 生成图片工具 —— Function Calling 实现。
 * 调用阿里百炼 DashScope 文生图 API，将生成的图片下载到本地 data/images/ 目录，
 * 返回本地路径供后续 Word 文档插入等操作使用。
 */
@Component
public class GenerateImageTool implements ToolDefinition {

    private static final Logger log = LoggerFactory.getLogger(GenerateImageTool.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String IMAGES_DIR = "data/images";

    private final RestTemplate restTemplate;

    @Value("${llm.draw-base-url}")
    private String drawBaseUrl;

    @Value("${llm.draw-model}")
    private String drawModel;

    @Value("${llm.draw-api-key}")
    private String drawApiKey;

    public GenerateImageTool(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public String getName() {
        return "generate_image";
    }

    @Override
    public String domain() {
        return "draw";
    }

    @Override
    public Map<String, Object> getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("prompt", Map.of(
                "type", "string",
                "description", "英文图像生成提示词，描述要生成的画面内容"
        ));

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of("prompt"));

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", getName());
        function.put("description", "AI生成图片。当用户要求画图、生成图片时调用，返回本地图片路径，可用于后续插入Word文档等操作。");
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
            String prompt = args.has("prompt") ? args.get("prompt").asText() : null;
            if (prompt == null || prompt.isBlank()) {
                return "{\"error\": \"生成提示词为空\"}";
            }

            log.info("生成图片: prompt={}", prompt);

            // 1. 调用绘图 API 获取图片字节
            byte[] imageBytes = callDrawApi(prompt);

            // 2. 保存到本地
            Files.createDirectories(Paths.get(IMAGES_DIR));
            String filename = "image_" + Instant.now().toEpochMilli() + ".png";
            Path localPath = Paths.get(IMAGES_DIR, filename);
            Files.write(localPath, imageBytes);

            String localPathStr = localPath.toString().replace("\\", "/");
            log.info("图片已生成并保存到本地: {}", localPathStr);

            return "{\"success\": true, \"operation\": \"generate_image\", " +
                    "\"local_path\": \"" + localPathStr + "\", " +
                    "\"message\": \"图片已生成，本地路径: " + localPathStr + "\"}";

        } catch (Exception e) {
            log.error("生成图片失败", e);
            return "{\"error\": \"生成图片失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 调用阿里百炼 DashScope 文生图 API。
     */
    private byte[] callDrawApi(String prompt) throws Exception {
        String apiUrl = drawBaseUrl.replaceAll("/+$", "")
                + "/api/v1/services/aigc/multimodal-generation/generation";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + drawApiKey);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", drawModel);

        List<Map<String, String>> content = new ArrayList<>();
        Map<String, String> textPart = new HashMap<>();
        textPart.put("text", prompt);
        content.add(textPart);

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

        String jsonToSend = objectMapper.writeValueAsString(requestBody);
        HttpEntity<String> request = new HttpEntity<>(jsonToSend, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(apiUrl, request, String.class);
        String responseBody = response.getBody();

        JsonNode root = objectMapper.readTree(responseBody);

        if (root.has("code") && root.has("message")) {
            throw new RuntimeException("生图 API 返回错误 [" + root.get("code").asText()
                    + "]: " + root.get("message").asText());
        }

        String imageUrl = null;
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
            throw new RuntimeException("生图 API 返回结果为空");
        }

        return restTemplate.getForObject(URI.create(imageUrl), byte[].class);
    }
}