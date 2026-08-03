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
                reply = chatDrawService.draw(userId, text);
            } else if ("3".equals(intentResult)) {
                String speechText = chatTextService.generateSpeechText(userId, text);
                if (speechText != null && !speechText.startsWith("【")) {
                    byte[] audioBytes = chatTTSService.synthesize(userId, speechText);
                    if (audioBytes != null) {
                        try {
                            iintService.sendFile(botId, userId, audioBytes, "reply.mp3", null);
                            chatTextService.recordAssistantReply(userId, speechText);
                        } catch (Exception e) {
                            log.error("发送语音失败", e);
                            reply = speechText;
                        }
                    } else {
                        reply = speechText;
                    }
                }
            } else {
                reply = chatTextService.chat(userId, text);
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
}
