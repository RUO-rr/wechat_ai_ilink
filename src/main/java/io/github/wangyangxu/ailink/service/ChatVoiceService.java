package io.github.wangyangxu.ailink.service;

import com.github.wechat.ilink.sdk.core.model.VoiceItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;

/**
 * 语音消息处理服务（SDK 方案，无需 ngrok）。
 * <p>
 * 流程：
 * <pre>
 *   用户发语音 → 通知用户 → 下载 .silk → 去头 → ChatSTTService 识别 → ChatTextService 聊天 → 回复
 * </pre>
 */
@Service
public class ChatVoiceService {

    private static final Logger log = LoggerFactory.getLogger(ChatVoiceService.class);

    @Autowired
    private IintService iintService;

    @Autowired
    private ChatSTTService chatSTTService;

    @Autowired
    private ChatTextService chatTextService;

    public String chat(String userId, VoiceItem voiceItem) {
        // ① 立即通知
        try {
            iintService.sendText(BotContext.currentBotId(), userId, "语音识别中，请稍候...");
        } catch (Exception e) {
            log.error("发送等待提示失败: {}", e.getMessage());
        }

        try {
            // ② 下载 + 去头（去掉 WeChat 的 0x02 前缀）
            byte[] voiceBytes = iintService.downloadMedia(BotContext.currentBotId(), voiceItem.getMedia());
            byte[] stripped = Arrays.copyOfRange(voiceBytes, 1, voiceBytes.length);
            log.info("语音下载成功 userId={}, 去头后大小: {} bytes", userId, stripped.length);

            // ③ SDK 识别（SILK→PCM→文字，一步到位）
            String recognizedText = chatSTTService.recognize(stripped);
            if (recognizedText == null || recognizedText.isBlank()) {
                trySendText(userId, "【错误】语音识别失败，请重试");
                return null;
            }
            log.info("语音识别成功: {}", recognizedText);

            // ④ 交给 AI 对话
            String aiReply = chatTextService.chat(userId, recognizedText);
            if (aiReply != null && !aiReply.startsWith("【")) {
                iintService.sendText(BotContext.currentBotId(), userId, aiReply);
            }

        } catch (Exception e) {
            log.error("语音识别流程异常: {}", e.getMessage(), e);
            trySendText(userId, "【错误】语音识别失败，请重试");
        }
        return null;
    }

    private void trySendText(String userId, String msg) {
        try {
            iintService.sendText(BotContext.currentBotId(), userId, msg);
        } catch (Exception e) {
            log.error("发送消息失败: {}", e.getMessage());
        }
    }
}
