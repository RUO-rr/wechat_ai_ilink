package com.example.wea_forecast.service;

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
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ChatTextService {

    private static final Logger log = LoggerFactory.getLogger(ChatTextService.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private ConversationHistory history;

    @Autowired
    private UserVoiceState userVoiceState;

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

    /**
     * 意图检测：判断用户消息是想画图、文字回复、语音回复还是切换音色。
     * 不修改对话历史。
     *
     * @return 格式说明：
     *         "1"          = 画图
     *         "2"          = 文字回复（默认）
     *         "3"          = 语音回复
     *         "4:龙晓晓"    = 切换音色（冒号后是要切换的音色中文名或英文ID）
     */
    public String detectIntent(String userId, String userMessage) {
        if (baseUrl == null || baseUrl.isBlank() || model == null || model.isBlank()
                || apiKey == null || apiKey.isBlank()) {
            log.warn("文本模型未配置，默认走文字回复");
            return "2";
        }

        // 把所有支持的音色名称拼成提示文本
        String voiceNames = String.join("、", userVoiceState.getSupportedVoiceNames());

        String apiUrl = baseUrl.replaceAll("/+$", "") + "/v1/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> sysMsg = new HashMap<>();
        sysMsg.put("role", "system");
        sysMsg.put("content", "请根据用户消息判断意图，只返回以下格式之一：\n"
                + "1 = 画图\n2 = 文字回复\n3 = 语音回复\n"
                + "4:音色名 = 切换音色（支持：" + voiceNames + "）\n"
                + "示例：用户说「切换音色为龙安欢」→ 返回 4:龙安欢\n"
                + "示例：用户说「语音回复我说今天天气」→ 返回 3\n"
                + "只返回格式中的内容，不要有多余文字。");
        messages.add(sysMsg);

        Map<String, String> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("max_tokens", 100);
        requestBody.put("temperature", 0);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    apiUrl, new HttpEntity<>(requestBody, headers), String.class);
            String body = response.getBody();
            log.info("意图检测原始响应: {}", body);
            if (body != null && !body.isBlank()) {
                JsonNode root = objectMapper.readTree(body);
                JsonNode msgNode = root.get("choices").get(0).get("message");

                String content = msgNode.get("content").asText().trim();
                if (content.isEmpty()) {
                    content = msgNode.get("reasoning_content").asText().trim();
                }

                log.info("意图检测结果: userId={}, rawResult={}", userId, content);

                // 提取意图数字（可能格式为 "4:龙晓晓" 或 "1" 等）
                if (content.startsWith("4")) return content;   // 返回完整 "4:音色名"
                if (content.contains("1")) return "1";
                if (content.contains("3")) return "3";
            }
        } catch (Exception e) {
            log.error("意图检测失败，默认走文字回复: {}", e.getMessage());
        }
        return "2";
    }

    /**
     * 为语音回复生成专用的 TTS 文字。
     * 不经过 chat()，避免 LLM 生成"我无法合成语音"之类的话。
     * <p>
     * 逻辑：直接用语音专用 system prompt 调 LLM，
     * 告诉它它的回答将通过 TTS 语音播放，让它用自然口语化的方式回答。
     * 不修改对话历史（由调用方负责后续 recordAssistantReply）。
     *
     * @param userId      微信用户 ID
     * @param userMessage 用户原始消息
     * @return 适合 TTS 播报的短文字，失败返回 null
     */
    public String generateSpeechText(String userId, String userMessage) {
        if (baseUrl == null || baseUrl.isBlank() || model == null || model.isBlank()
                || apiKey == null || apiKey.isBlank()) {
            log.error("文本模型未配置，无法生成语音文字");
            return null;
        }

        String apiUrl = baseUrl.replaceAll("/+$", "") + "/v1/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        // 构建消息列表：语音专用 system prompt + 对话历史 + 当前用户消息
        List<Map<String, String>> messages = new ArrayList<>();

        Map<String, String> sysMsg = new HashMap<>();
        sysMsg.put("role", "system");
        sysMsg.put("content", "你的名字叫小安，是一个语音助手。你的回答会通过 TTS 语音直接播放给用户听。\n"
                + "要求：\n"
                + "1. 使用自然口语化的短句，像真人聊天一样\n"
                + "2. 回答控制在 100 字以内，适合朗读\n"
                + "3. 不要使用 Markdown、列表、代码等格式\n"
                + "4. 不要说你「无法说话」「不能发声」「无法合成语音」之类的话——你就是一个能说话的语音助手\n"
                + "5. 直接用文字回答，不要说「这是语音回复」之类的前缀");
        messages.add(sysMsg);

        // 带上最近的对话历史（让回复有上下文），但 system prompt 已经被 speech prompt 替换
        List<Map<String, String>> snapshot = history.getSnapshot(userId);
        if (snapshot != null) {
            for (Map<String, String> msg : snapshot) {
                if (!"system".equals(msg.get("role"))) {
                    messages.add(msg);
                }
            }
        }

        // 追加当前用户消息
        Map<String, String> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("max_tokens", 300);     // 语音回复短一些
        requestBody.put("temperature", 0.7);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    apiUrl, new HttpEntity<>(requestBody, headers), String.class);
            String body = response.getBody();
            log.info("语音文本生成原始响应: {}", body);

            if (body != null && !body.isBlank()) {
                JsonNode root = objectMapper.readTree(body);
                String content = root.get("choices").get(0).get("message").get("content").asText();
                if (content != null && !content.isBlank()) {
                    log.info("语音文本: {}", content);
                    return content.trim();
                }
            }
        } catch (Exception e) {
            log.error("语音文本生成失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 记录助手的回复到对话历史（用于语音回复等非 chat() 路径）。
     */
    public void recordAssistantReply(String userId, String assistantText) {
        history.getOrCreate(userId);
        history.addMessage(userId, "assistant", assistantText);
        history.trim(userId);
    }

    public String chat(String userId, String userMessage) {
        if (baseUrl == null || baseUrl.isBlank()) {
            log.error("未配置 llm.base-url");
            return "【系统错误】未配置文本模型 API 地址";
        }
        if (model == null || model.isBlank()) {
            log.error("未配置 llm.model");
            return "【系统错误】未配置文本模型名称";
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.error("未配置 llm.api-key");
            return "【系统错误】未配置文本模型 API Key";
        }

        // 1. 获取或创建该用户的对话历史
        history.getOrCreate(userId);

        // 2. 追加用户消息
        history.addMessage(userId, "user", userMessage);

        // 3. 构建请求
        String apiUrl = baseUrl.replaceAll("/+$", "") + "/v1/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", history.getSnapshot(userId));
        requestBody.put("max_tokens", maxTokens);
        requestBody.put("temperature", temperature);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        // 4. 调用 API
        String responseBody;
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(apiUrl, request, String.class);
            responseBody = response.getBody();
        } catch (RestClientException e) {
            log.error("调用大模型 API 失败: {}", e.getMessage(), e);
            return "【网络错误】调用大模型失败：" + e.getMessage();
        }

        // 5. 解析响应
        String assistantContent;
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            assistantContent = root.get("choices").get(0).get("message").get("content").asText();
        } catch (Exception e) {
            log.error("解析大模型响应失败, body={}: {}", responseBody, e.getMessage(), e);
            return "【数据错误】解析大模型响应失败";
        }

        // 6. 追加助手回复到历史
        history.addMessage(userId, "assistant", assistantContent);

        // 7. 裁剪历史
        history.trim(userId);

        log.info("文本对话完成 userId={}", userId);
        return assistantContent;
    }
}