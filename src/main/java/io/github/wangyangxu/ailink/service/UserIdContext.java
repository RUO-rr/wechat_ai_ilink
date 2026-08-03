package io.github.wangyangxu.ailink.service;

/**
 * 当前请求的 userId 上下文（ThreadLocal），委托给 BotContext。
 * 保留此类仅为了向后兼容，新代码应直接使用 BotContext。
 *
 * @deprecated 使用 {@link BotContext#currentUserId()} 替代
 */
@Deprecated
public class UserIdContext {

    public static void set(String userId) { BotContext.set(new BotContext("legacy", userId)); }
    public static String get() { return BotContext.currentUserId(); }
    public static void clear() { BotContext.clear(); }

    private UserIdContext() {}
}
