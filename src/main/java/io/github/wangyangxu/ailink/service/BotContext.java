package io.github.wangyangxu.ailink.service;

import java.util.UUID;

/**
 * 请求上下文 —— 封装 (botId, wechatUserId, traceId) 三元组。
 * 通过 ThreadLocal 在请求链路中透传，入口在 MainController.handleMessage()。
 *
 * <h3>身份语义</h3>
 * <ul>
 *   <li><b>botId</b> — 系统内部的 Bot 标识（如 {@code bot_a1b2c3d4}），
 *       对应接收消息的那个 Bot 实例</li>
 *   <li><b>userId</b> — 消息发送者的微信 ID（来自 {@code msg.from_user_id}），
 *       SDK 保证是真实的微信用户标识</li>
 * </ul>
 */
public class BotContext {

    private static final ThreadLocal<BotContext> CURRENT = new ThreadLocal<>();

    private final String botId;
    private final String userId;
    private final String traceId;

    public BotContext(String botId, String wechatUserId) {
        this.botId = botId;
        this.userId = wechatUserId;
        this.traceId = UUID.randomUUID().toString().substring(0, 8);
    }

    /** @return 系统内部 Bot 标识 */
    public String getBotId() { return botId; }
    /** @return 消息发送者的微信 ID */
    public String getUserId() { return userId; }
    /** @return 请求追踪 ID */
    public String getTraceId() { return traceId; }

    /** @return 消息发送者的微信 ID（语义更明确的别名） */
    public static String currentWechatUserId() {
        BotContext ctx = CURRENT.get();
        return ctx != null ? ctx.userId : null;
    }

    /** 兼容旧代码：获取当前请求的微信用户 ID */
    public static String currentUserId() {
        BotContext ctx = CURRENT.get();
        return ctx != null ? ctx.userId : null;
    }

    /** @return 当前请求的 Bot ID */
    public static String currentBotId() {
        BotContext ctx = CURRENT.get();
        return ctx != null ? ctx.botId : null;
    }

    public static BotContext get() { return CURRENT.get(); }

    public static void set(BotContext ctx) { CURRENT.set(ctx); }

    public static void clear() { CURRENT.remove(); }

    private BotContext() { throw new UnsupportedOperationException(); }
}
