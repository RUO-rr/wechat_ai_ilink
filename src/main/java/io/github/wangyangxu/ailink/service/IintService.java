package io.github.wangyangxu.ailink.service;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.CDNMedia;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * 多 Bot 消息发送服务 —— 通过 botId 从 BotManager 获取对应 client，
 * 不再持有单一 client 实例。
 */
@Service
public class  IintService {

    private static final Logger log = LoggerFactory.getLogger(IintService.class);

    private final BotManager botManager;

    public IintService(BotManager botManager) {
        this.botManager = botManager;
    }

    private ILinkClient getClient(String botId) {
        var bot = botManager.getBot(botId);
        if (bot == null || bot.getClient() == null) {
            throw new IllegalStateException("Bot " + botId + " 未就绪");
        }
        // 路由验证：确保请求的 botId 和实际 client 匹配
        BotContext ctx = BotContext.get();
        if (ctx != null && !botId.equals(ctx.getBotId())) {
            log.error("路由验证失败！ctx.botId={} != 请求 botId={}", ctx.getBotId(), botId);
            throw new IllegalStateException("发送路由不匹配: ctx.botId=" + ctx.getBotId() + " != " + botId);
        }
        return bot.getClient();
    }

    // ======================== 发送方法（botId 必传） ========================

    public void sendText(String botId, String toUserId, String text) throws Exception {
        getClient(botId).sendText(toUserId, text);
    }

    public void sendVoice(String botId, String toUserId, byte[] voiceBytes, String fileName,
                          Integer playTimeMs, Integer sampleRate) throws IOException {
        getClient(botId).sendVoice(toUserId, voiceBytes, fileName, playTimeMs, sampleRate);
    }

    public void sendFile(String botId, String toUserId, byte[] fileBytes, String fileName, String caption) throws IOException {
        getClient(botId).sendFile(toUserId, fileBytes, fileName, caption);
    }

    public void sendImage(String botId, String toUserId, byte[] imageBytes, String fileName, String caption) throws IOException {
        getClient(botId).sendImage(toUserId, imageBytes, fileName, caption);
    }

    public byte[] downloadMedia(String botId, CDNMedia media) throws IOException {
        return getClient(botId).downloadMedia(media);
    }
}
