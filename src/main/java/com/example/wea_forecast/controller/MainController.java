package com.example.wea_forecast.controller;

import com.example.wea_forecast.service.ChatDrawService;
import com.example.wea_forecast.service.ChatFileService;
import com.example.wea_forecast.service.ChatImageService;
import com.example.wea_forecast.service.ChatTTSService;
import com.example.wea_forecast.service.ChatTextService;
import com.example.wea_forecast.service.ChatVoiceService;
import com.example.wea_forecast.service.IlinkService;
import com.example.wea_forecast.service.UserVoiceState;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class MainController implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(MainController.class);

    @Autowired
    private ChatTextService chatTextService;

    @Autowired
    private ChatImageService chatImageService;

    @Autowired
    private ChatVoiceService chatVoiceService;

    @Autowired
    private ChatDrawService chatDrawService;

    @Autowired
    private ChatTTSService chatTTSService;

    @Autowired
    private ChatFileService chatFileService;

    @Autowired
    private UserVoiceState userVoiceState;

    @Autowired
    private IlinkService ilinkService;

    @Override
    public void run(String... args) throws Exception {
        // ===== iLink 初始化 =====
        ilinkService.initialize(
                new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(com.github.wechat.ilink.sdk.core.login.LoginContext ctx) {
                        log.info("登录成功! BotId={}", ctx.getBotId());
                    }

                    @Override
                    public void onLoginFailure(Throwable t) {
                        log.error("登录失败: {}", t.getMessage(), t);
                    }
                },
                new OnMessageListener() {
                    @Override
                    public void onMessages(List<WeixinMessage> messages) {
                        for (WeixinMessage msg : messages) {
                            handleMessage(msg);
                        }
                    }
                }
        );

        String qrCode = ilinkService.login();
        log.info("========== 请扫码登录 ==========");
        log.info(qrCode);
        log.info("================================");

        try {
            ilinkService.getLoginFuture().get(3, TimeUnit.MINUTES);
            log.info("Bot 已就绪，等待微信消息...");
        } catch (Exception e) {
            log.error("登录超时或失败，程序退出", e);
            ilinkService.destroy();
            throw new RuntimeException("登录失败", e);
        }
    }

    private void handleMessage(WeixinMessage msg) {
        String fromUserId = msg.getFrom_user_id();
        List<MessageItem> items = msg.getItem_list();
        if (items == null) {
            return;
        }

        for (MessageItem item : items) {
            String reply = null;

            // ===== 文本消息 =====
            if (item.getText_item() != null) {
                String text = item.getText_item().getText().trim();
                log.info("收到文本消息 from={} text={}", fromUserId, text);

                // detectIntent 返回格式：
                //   "1"       → 画图
                //   "2"       → 文字回复（默认）
                //   "3"       → 语音回复
                //   "4:音色名" → 切换音色
                String intentResult = chatTextService.detectIntent(fromUserId, text);

                if (intentResult != null && intentResult.startsWith("4")) {
                    // ===== 意图 4：切换音色 =====
                    String voiceName = intentResult.contains(":")
                            ? intentResult.substring(intentResult.indexOf(":") + 1).trim()
                            : null;

                    if (voiceName != null && !voiceName.isBlank()) {
                        // 用 UserVoiceState 自带的模糊匹配查找音色 ID
                        final String resolvedVoiceId = userVoiceState.findVoiceId(voiceName);

                        if (resolvedVoiceId != null) {
                            userVoiceState.setVoice(fromUserId, resolvedVoiceId);
                            String chineseName = UserVoiceState.SUPPORTED_VOICES.entrySet().stream()
                                    .filter(entry -> entry.getValue().equals(resolvedVoiceId))
                                    .map(Map.Entry::getKey)
                                    .findFirst().orElse(resolvedVoiceId);
                            reply = "✅ 已切换音色为「" + chineseName + "」，下次语音回复时将使用该音色。\n支持的音色：" + String.join("、", userVoiceState.getSupportedVoiceNames());
                        } else {
                            reply = "❌ 未找到音色「" + voiceName + "」\n支持的音色：" + String.join("、", userVoiceState.getSupportedVoiceNames());
                        }
                    } else {
                        reply = "❌ 未识别到音色名称。支持的音色：" + String.join("、", userVoiceState.getSupportedVoiceNames());
                    }

                } else if ("1".equals(intentResult)) {
                    // ===== 意图 1：画图 =====
                    reply = chatDrawService.draw(fromUserId, text);

                } else if ("3".equals(intentResult)) {
                    // ===== 意图 3：语音回复 =====
                    // 使用语音专用文本生成（不调用 chat，避免 LLM 说"无法合成语音"）
                    String speechText = chatTextService.generateSpeechText(fromUserId, text);
                    if (speechText != null && !speechText.startsWith("【")) {
                        byte[] audioBytes = chatTTSService.synthesize(fromUserId, speechText);
                        if (audioBytes != null) {
                            try {
                                ilinkService.sendFile(fromUserId, audioBytes, "reply.mp3", null);
                                log.info("语音文件发送成功 to={}", fromUserId);
                                // 把语音内容也记入对话历史
                                chatTextService.recordAssistantReply(fromUserId, speechText);
                            } catch (Exception e) {
                                log.error("发送语音失败，降级发文字: {}", e.getMessage());
                                reply = speechText;
                            }
                        } else {
                            log.warn("TTS 合成失败，降级发文字");
                            reply = speechText;
                        }
                    }

                } else {
                    // ===== 意图 2：文字回复（默认） =====
                    reply = chatTextService.chat(fromUserId, text);
                }

            // ===== 图片消息 =====
            } else if (item.getImage_item() != null) {
                log.info("收到图片消息 from={}", fromUserId);
                reply = chatImageService.chat(fromUserId, item.getImage_item());

            // ===== 语音消息 =====
            } else if (item.getVoice_item() != null) {
                log.info("收到语音消息 from={}", fromUserId);
                reply = chatVoiceService.chat(fromUserId, item.getVoice_item());

            // ===== 文件消息（Word/PDF/Excel/TXT 等） =====
            } else if (item.getFile_item() != null) {
                log.info("收到文件消息 from={}, fileName={}", fromUserId, item.getFile_item().getFile_name());
                reply = chatFileService.chat(fromUserId, item.getFile_item());
            }

            // ===== 发送回复 =====
            if (reply != null) {
                try {
                    ilinkService.sendText(fromUserId, reply);
                } catch (Exception e) {
                    log.error("回复消息失败 to={}: {}", fromUserId, e.getMessage(), e);
                }
            }
        }
    }
}