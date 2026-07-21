package com.example.wea_forecast.service;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;

/**
 * 语音识别服务（DashScope 官方 SDK 方案）。
 * <p>
 * 流程：
 * <pre>
 *   SILK byte[] → decoder(SILK→PCM) → Recognition.call(PCM) → 文字
 * </pre>
 * 一次同步调用，不需要 ngrok / URL / 异步轮询。
 */
@Service
public class ChatSTTService {

    private static final Logger log = LoggerFactory.getLogger(ChatSTTService.class);
    private static final int SAMPLE_RATE = 16000;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${llm.stt-api-key}")
    private String apiKey;

    @Value("${asr.model:paraformer-realtime-v2}")
    private String asrModel;

    @Value("${silk.decoder-path:silk_v3_decoder.exe}")
    private String silkDecoderPath;

    /**
     * 识别 SILK 格式语音，返回文字。
     */
    public String recognize(byte[] silkBytes) {
        byte[] pcmBytes = decodeSilkToPcm(silkBytes);
        return recognizePcm(pcmBytes);
    }

    /** SILK → PCM（通过 silk_v3_decoder） */
    private byte[] decodeSilkToPcm(byte[] silkBytes) {
        File silkFile = null;
        File pcmFile = null;
        try {
            silkFile = File.createTempFile("voice_silk_", ".silk");
            pcmFile = File.createTempFile("voice_pcm_", ".pcm");
            try (FileOutputStream fos = new FileOutputStream(silkFile)) {
                fos.write(silkBytes);
            }

            ProcessBuilder pb = new ProcessBuilder(
                    silkDecoderPath,
                    silkFile.getAbsolutePath(),
                    pcmFile.getAbsolutePath(),
                    "-Fs_API", String.valueOf(SAMPLE_RATE)
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("SILK解码失败(exit=" + exitCode + "): " + output);
            }
            byte[] pcmBytes = Files.readAllBytes(pcmFile.toPath());
            log.info("SILK解码完成，PCM大小: {} bytes", pcmBytes.length);
            return pcmBytes;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("SILK解码异常: " + e.getMessage(), e);
        } finally {
            if (silkFile != null) silkFile.delete();
            if (pcmFile != null) pcmFile.delete();
        }
    }

    /** PCM → 文字（DashScope WebSocket SDK） */
    private String recognizePcm(byte[] pcmBytes) {
        File pcmFile = null;
        try {
            pcmFile = File.createTempFile("asr_pcm_", ".pcm");
            try (FileOutputStream fos = new FileOutputStream(pcmFile)) {
                fos.write(pcmBytes);
            }

            Recognition recognizer = new Recognition();
            RecognitionParam param = RecognitionParam.builder()
                    .model(asrModel)
                    .apiKey(apiKey)
                    .format("pcm")
                    .sampleRate(SAMPLE_RATE)
                    .build();

            String rawResult;
            try {
                rawResult = recognizer.call(param, pcmFile);
            } finally {
                recognizer.getDuplexApi().close(1000, "bye");
            }

            log.debug("ASR原始返回: {}", rawResult);
            String text = extractText(rawResult);
            log.info("语音识别结果: {}", text);
            return text;
        } catch (Exception e) {
            throw new RuntimeException("语音识别失败: " + e.getMessage(), e);
        } finally {
            if (pcmFile != null) pcmFile.delete();
        }
    }

    /** 从 ASR 返回的 JSON 中提取识别文字 */
    private String extractText(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode sentences = root.get("sentences");
            if (sentences == null || !sentences.isArray()) {
                log.warn("ASR 结果中无 sentences 字段，返回原始 JSON");
                return json;
            }
            StringBuilder sb = new StringBuilder();
            for (JsonNode s : sentences) {
                if (s.has("sentence_end") && s.get("sentence_end").asBoolean()
                        && s.has("text")) {
                    sb.append(s.get("text").asText());
                }
            }
            return sb.isEmpty() ? json : sb.toString();
        } catch (Exception e) {
            log.error("解析ASR结果失败: {}", e.getMessage());
            return json;
        }
    }
}
