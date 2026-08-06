package io.github.wangyangxu.ailink.controller;

import io.github.wangyangxu.ailink.service.*;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import io.github.wangyangxu.ailink.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 多 Bot 消息入口 —— 不再持有单个 ILinkClient，而是通过 BotManager 路由。
 * 消息到达时设置 BotContext（ThreadLocal），所有下游服务通过 BotContext 获取 botId/userId。
 */
@Component
public class MainController implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(MainController.class);

    @Autowired private ChatTextService chatTextService;
    @Autowired private ChatImageService chatImageService;
    @Autowired private ChatVoiceService chatVoiceService;
    @Autowired private ChatDrawService chatDrawService;
    @Autowired private ChatTTSService chatTTSService;
    @Autowired private ChatFileService chatFileService;
    @Autowired private UserVoiceState userVoiceState;
    @Autowired private IintService iintService;
    @Autowired private BotManager botManager;

    @Override
    public void run(String... args) {
        // 注册消息处理器到 BotManager
        botManager.registerMessageHandler(this::handleMessage);
        log.info("多 Bot 消息路由已就绪，等待 Bot 登录...");
    }

    private void handleMessage(String botId, WeixinMessage msg) {
        String fromUserId = msg.getFrom_user_id();
        List<MessageItem> items = msg.getItem_list();
        if (items == null) return;

        // 设置上下文（ThreadLocal），下游服务可通过 BotContext.get() 获取
        BotContext ctx = new BotContext(botId, fromUserId);
        BotContext.set(ctx);
        MDC.put("botId", botId);
        MDC.put("traceId", ctx.getTraceId());
        try {
            for (MessageItem item : items) {
                processItem(botId, fromUserId, item);
            }
        } finally {
            BotContext.clear();
            MDC.remove("botId");
            MDC.remove("traceId");
        }
    }

    private void processItem(String botId, String userId, MessageItem item) {
        String reply = null;

        // ===== 文本消息 =====
        if (item.getText_item() != null) {
            String text = item.getText_item().getText().trim();
            log.debug("收到文本消息 len={}", text.length());

            String intentResult = chatTextService.detectIntent(userId, text);

            if (intentResult != null && intentResult.startsWith("4")) {
                String voiceName = intentResult.contains(":")
                        ? intentResult.substring(intentResult.indexOf(":") + 1).trim() : null;
                if (voiceName != null && !voiceName.isBlank()) {
                    String resolvedVoiceId = userVoiceState.findVoiceId(voiceName);
                    if (resolvedVoiceId != null) {
                        userVoiceState.setVoice(userId, resolvedVoiceId);
                        reply = "已切换音色为" + resolvedVoiceId;
                    } else {
                        reply = "未找到音色「" + voiceName + "」";
                    }
                }
            } else if ("1".equals(intentResult)) {
                try {
                    reply = chatDrawService.draw(userId, text);
                } catch (Exception e) {
                    log.error("画图失败，走文本兜底", e);
                    reply = serviceFallback(botId, userId, "画图服务暂不可用：" + e.getMessage(), text, true);
                }
            } else if ("3".equals(intentResult)) {
                reply = handleVoiceReply(botId, userId, text, false);
            } else {
                reply = chatTextService.chat(userId, text);
                // 意图协议：弱信号意图由 LLM 在 FC 首轮识别（[VOICE] / [VOICE_SWITCH:xx]）
                if (reply != null && ChatTextService.isIntentMarker(reply)) {
                    if (reply.startsWith("[VOICE]")) {
                        // chat() 已把用户消息写入历史，兜底时避免重复
                        reply = handleVoiceReply(botId, userId, text, true);
                    } else if (reply.startsWith("[VOICE_SWITCH:")) {
                        String voiceName = extractVoiceSwitchName(reply);
                        String resolved = voiceName == null ? null : userVoiceState.findVoiceId(voiceName);
                        if (resolved != null) {
                            userVoiceState.setVoice(userId, resolved);
                            reply = "已切换音色为" + resolved;
                        } else {
                            reply = voiceName == null ? "未识别到要切换的音色" : "未找到音色「" + voiceName + "」";
                        }
                    }
                }
            }

        // ===== 图片消息 =====
        } else if (item.getImage_item() != null) {
            log.info("收到图片消息");
            reply = chatImageService.chat(userId, item.getImage_item());

        // ===== 语音消息 =====
        } else if (item.getVoice_item() != null) {
            log.info("收到语音消息");
            reply = chatVoiceService.chat(userId, item.getVoice_item());

        // ===== 文件消息 =====
        } else if (item.getFile_item() != null) {
            log.info("收到文件消息: {}", item.getFile_item().getFile_name());
            reply = chatFileService.chat(userId, item.getFile_item());
        }

        // ===== 发送回复 =====
        if (reply != null) {
            try {
                iintService.sendText(botId, userId, reply);
            } catch (Exception e) {
                log.error("回复消息失败", e);
            }
        }
    }

    /**
     * 语音回复流程：生成语音文本 → TTS 合成 → 发送。
     * 失败时提示原因并回退文本回复。
     *
     * @param userMsgInHistory 用户消息是否已在对话历史中（硬路由=false，标记协议=true）
     */
    private String handleVoiceReply(String botId, String userId, String text, boolean userMsgInHistory) {
        String speechText = chatTextService.generateSpeechText(userId, text);
        if (speechText == null) {
            return serviceFallback(botId, userId, "语音文本生成失败", text, userMsgInHistory);
        }
        try {
            byte[] audioBytes = chatTTSService.synthesize(userId, speechText);
            if (audioBytes == null) {
                return serviceFallback(botId, userId, "语音合成失败", text, userMsgInHistory);
            }
            iintService.sendFile(botId, userId, audioBytes, "reply.mp3", null);
            chatTextService.recordAssistantReply(userId, speechText);
            return null;
        } catch (Exception e) {
            log.error("发送语音失败", e);
            return serviceFallback(botId, userId, "语音发送失败：" + e.getMessage(), text, userMsgInHistory);
        }
    }

    /**
     * 硬路由服务失败兜底：先提示用户原因，再走文本回复（FC），避免回复出错。
     *
     * @param userMsgInHistory 失败服务是否已把用户消息写入历史（画图=true，语音=false）
     */
    private String serviceFallback(String botId, String userId, String reason,
                                   String originalText, boolean userMsgInHistory) {
        try {
            iintService.sendText(botId, userId, "因为" + reason + "，暂时不能提供服务，请稍后再试或换一种说法");
        } catch (Exception e) {
            log.warn("兜底提示发送失败: {}", e.getMessage());
        }
        return userMsgInHistory
                ? chatTextService.chatFallback(userId)
                : chatTextService.chat(userId, originalText);
    }

    private static String extractVoiceSwitchName(String marker) {
        int start = "[VOICE_SWITCH:".length();
        int end = marker.indexOf(']', start);
        if (end < 0) return null;
        String name = marker.substring(start, end).trim();
        return name.isEmpty() ? null : name;
    }
}
