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
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * 语音合成服务：把文字转成语音（TTS = Text To Speech）。
 * <p>
 * 流程分两步：
 * <pre>
 *   ① POST CosyVoice API → 返回 JSON（含音频下载 URL）
 *   ② GET 下载 URL → 拿到真正的音频二进制数据
 * </pre>
 */
@Service
public class ChatTTSService {

    private static final Logger log = LoggerFactory.getLogger(ChatTTSService.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private UserVoiceState userVoiceState;

    @Value("${llm.tts-base-url}")
    private String ttsBaseUrl;

    @Value("${llm.tts-model}")
    private String ttsModel;

    @Value("${llm.tts-api-key}")
    private String ttsApiKey;

    /**
     * 把文字合成语音（使用用户设置的音色）。
     *
     * @param userId 微信用户 ID（用于查找该用户设置的音色）
     * @param text   要合成的文字（中英文均可）
     * @return 音频字节数组（mp3 格式），失败返回 null
     */
    public byte[] synthesize(String userId, String text) {
        if (ttsBaseUrl == null || ttsBaseUrl.isBlank()
                || ttsModel == null || ttsModel.isBlank()
                || ttsApiKey == null || ttsApiKey.isBlank()) {
            log.error("TTS 未配置，无法合成语音");
            return null;
        }
        if (text == null || text.isBlank()) {
            log.warn("要合成的文字为空，跳过");
            return null;
        }

        // 1. 拼 URL
        String apiUrl = ttsBaseUrl.replaceAll("/+$", "")
                + "/api/v1/services/audio/tts/SpeechSynthesizer";

        // 2. 拼请求头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + ttsApiKey);

        // 3. 拼请求体
        // CosyVoice API：voice/format/sample_rate 都在 input 层
        Map<String, Object> input = new HashMap<>();
        input.put("text", text);
        // 从 UserVoiceState 动态读取该用户设置的音色，未设置的用户使用默认音色
        String voiceId = userVoiceState.getVoice(userId);
        input.put("voice", voiceId);
        log.info("TTS 使用音色: {} (userId={})", voiceId, userId);
        input.put("format", "mp3");           // mp3 体积小，适合微信
        input.put("sample_rate", 24000);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", ttsModel);
        requestBody.put("input", input);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        // 打印完整请求体 JSON，方便排查问题
        try {
            log.info("TTS 请求体 JSON: {}", objectMapper.writeValueAsString(requestBody));
        } catch (Exception ignored) {}

        log.info("🚀 请求 TTS API 的完整地址: {}", apiUrl);
        log.info("TTS 合成文字: {}", text);
        log.info("TTS 模型名: {}", ttsModel);
        try {
            // ① 调 TTS API → 返回 JSON（不是二进制音频！）
            ResponseEntity<String> response = restTemplate.postForEntity(
                    apiUrl, request, String.class);
            String responseBody = response.getBody();
            log.info("TTS 响应 JSON: {}", responseBody);

            JsonNode root = objectMapper.readTree(responseBody);

            // 先检查错误
            if (root.has("code") && root.has("message")) {
                log.error("TTS API 返回错误 [{}]: {}",
                        root.get("code").asText(), root.get("message").asText());
                return null;
            }

            // ② 从 JSON 中取音频下载 URL
            String audioUrl = root.path("output").path("audio").path("url").asText();
            if (audioUrl.isBlank()) {
                log.error("TTS 响应中没有音频 URL");
                return null;
            }

            // ③ 下载真正的音频文件（用 URI 避免签名 URL 被二次编码）
            log.info("下载 TTS 音频: {}", audioUrl);
            byte[] audioBytes = restTemplate.getForObject(URI.create(audioUrl), byte[].class);

            if (audioBytes != null && audioBytes.length > 0) {
                log.info("TTS 合成成功，音频大小: {} bytes", audioBytes.length);
                return audioBytes;
            } else {
                log.error("下载 TTS 音频为空");
                return null;
            }
        } catch (Exception e) {
            log.error("TTS 合成失败: {}", e.getMessage(), e);
            return null;
        }
    }
}
