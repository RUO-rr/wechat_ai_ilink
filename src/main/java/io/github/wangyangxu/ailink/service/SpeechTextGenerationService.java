package io.github.wangyangxu.ailink.service;

import io.github.wangyangxu.ailink.client.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 语音文本生成服务 —— 为 TTS 播报生成口语化的短文本。
 * 纯 LLM 调用，不修改对话历史（由调用方负责 recordAssistantReply）。
 */
@Service
public class SpeechTextGenerationService {

    private static final Logger log = LoggerFactory.getLogger(SpeechTextGenerationService.class);

    private final LlmClient llmClient;
    private final ConversationHistory history;

    @Value("${llm.base-url}")
    private String baseUrl;

    @Value("${llm.model}")
    private String model;

    @Value("${llm.api-key}")
    private String apiKey;

    public SpeechTextGenerationService(LlmClient llmClient, ConversationHistory history) {
        this.llmClient = llmClient;
        this.history = history;
    }

    /**
     * 生成适合 TTS 播报的口语化短文字。
     * @return 口语化文字，失败返回 null
     */
    public String generate(String userId, String userMessage) {
        if (baseUrl == null || baseUrl.isBlank() || model == null || model.isBlank()
                || apiKey == null || apiKey.isBlank()) {
            log.error("文本模型未配置，无法生成语音文字");
            return null;
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> sysMsg = new HashMap<>();
        sysMsg.put("role", "system");
        sysMsg.put("content", "你的名字叫小安，是一个语音助手。你的回答会通过 TTS 语音直接播放给用户听。\n"
                + "要求：\n"
                + "1. 使用自然口语化的短句，像真人聊天一样\n"
                + "2. 回答控制在 100 字以内，适合朗读\n"
                + "3. 不要使用 Markdown、列表、代码等格式\n"
                + "4. 不要说你「无法说话」「不能发声」「无法合成语音」之类的话——你就是一个能说话的语音助手\n"
                + "5. 直接用文字回答，不要说「这是语音回复」之类的前缀");
        messages.add(sysMsg);

        for (Map<String, Object> msg : history.getSnapshot(userId)) {
            if (!"system".equals(msg.get("role"))) {
                messages.add(msg);
            }
        }

        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("max_tokens", 300);
        requestBody.put("temperature", 0.7);

        try {
            JsonNode response = llmClient.callChatApi(baseUrl, apiKey, requestBody);
            JsonNode msgNode = LlmClient.extractMessage(response);
            String content = LlmClient.extractContent(msgNode);
            if (content != null && !content.isBlank()) {
                log.info("语音文本: {}", content);
                return content.trim();
            }
        } catch (Exception e) {
            log.error("语音文本生成失败", e);
        }
        return null;
    }
}
