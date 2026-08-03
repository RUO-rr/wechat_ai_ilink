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
 * 意图检测服务 —— 判断用户消息的目标：画图/文字/语音/切换音色。
 * 纯 LLM 调用，不修改对话历史。
 */
@Service
public class IntentDetectionService {

    private static final Logger log = LoggerFactory.getLogger(IntentDetectionService.class);

    private final LlmClient llmClient;
    private final UserVoiceState userVoiceState;

    @Value("${llm.base-url}")
    private String baseUrl;

    @Value("${llm.model}")
    private String model;

    @Value("${llm.api-key}")
    private String apiKey;

    public IntentDetectionService(LlmClient llmClient, UserVoiceState userVoiceState) {
        this.llmClient = llmClient;
        this.userVoiceState = userVoiceState;
    }

    /**
     * @return "1"=画图, "2"=文字回复, "3"=语音回复, "4:音色名"=切换音色
     */
    public String detect(String userId, String userMessage) {
        if (baseUrl == null || baseUrl.isBlank() || model == null || model.isBlank()
                || apiKey == null || apiKey.isBlank()) {
            log.warn("文本模型未配置，默认走文字回复");
            return "2";
        }

        String voiceNames = String.join("、", userVoiceState.getSupportedVoiceNames());

        // 关键词快速路由：表格/Excel/文档类任务不走 LLM 检测，避免误判为画图
        String trimmed = userMessage.trim();
        if (trimmed.contains("表格") || trimmed.contains("excel") || trimmed.contains("xlsx")
                || trimmed.contains("电子表格") || trimmed.contains("数据表")) {
            log.debug("关键词命中(表格/Excel)，直接路由到文字回复");
            return "2";
        }

        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> sysMsg = new HashMap<>();
        sysMsg.put("role", "system");
        sysMsg.put("content", "请根据用户消息判断意图，只返回以下格式之一：\n"
                + "1 = 画图\n2 = 文字回复\n3 = 语音回复\n"
                + "4:音色名 = 切换音色（支持：" + voiceNames + "）\n"
                + "示例：用户说「切换音色为龙安欢」→ 返回 4:龙安欢\n"
                + "示例：用户说「用语音回复我」→ 返回 3\n"
                + "只返回以上格式的纯文本，不要有多余文字、标点或解释。");
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
            JsonNode response = llmClient.callChatApi(baseUrl, apiKey, requestBody);
            JsonNode msgNode = LlmClient.extractMessage(response);
            String content = LlmClient.extractContentWithReasoningFallback(msgNode);

            log.debug("意图检测结果: userId={}, rawResult={}", userId, content);

            if (content.startsWith("4")) return content;
            // 使用精确匹配而非 contains，避免 LLM 输出非预期文本（如 "2（文字回复）"）误触发
            String clean = content.trim();
            if ("1".equals(clean)) return "1";
            if ("3".equals(clean)) return "3";
        } catch (Exception e) {
            log.error("意图检测失败，默认走文字回复", e);
        }
        return "2";
    }
}
