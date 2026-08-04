package io.github.wangyangxu.ailink.model;

import com.github.wechat.ilink.sdk.ILinkClient;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bot 实例模型 —— 每个扫码登录的微信用户拥有一个独立的 BotInstance。
 * 包含 ILinkClient、状态、二维码、线程池等所有运行时资源。
 *
 * <h3>双 ID 模型</h3>
 * <ul>
 *   <li><b>systemBotId</b> — 系统内部唯一标识，创建时自动生成，用于 Map key / REST API path / 线程命名</li>
 *   <li><b>wechatUserId</b> — SDK 返回的扫码者微信 ID（{@code LoginContext.userId}），是业务身份的信任根</li>
 *   <li><b>wechatBotId</b> — SDK 返回的微信 Bot ID（{@code LoginContext.botId}），用于与微信平台对接</li>
 * </ul>
 *
 * <p>身份在登录成功后才注入，创建时只有 systemBotId。消息到达时 {@code msg.from_user_id}
 * 与 {@code wechatUserId} 天然对齐（都是微信 ID 体系）。</p>
 */
public class BotInstance {

    /** 系统内部唯一标识，创建时分配，重启后复用 */
    private final String botId;

    /** SDK 返回的扫码者微信 ID，登录成功前为 null */
    private volatile String wechatUserId;

    /** SDK 返回的微信 Bot ID，登录成功前为 null */
    private volatile String wechatBotId;

    /** SDK 返回的 Bot Token，登录成功前为 null */
    private volatile String botToken;

    /** SDK 返回的 API Base URL，登录成功前为 null */
    private volatile String baseUrl;

    /** 展示标签（用户创建 Bot 时可选传入，仅用于管理界面展示） */
    private final String label;

    /** iLink SDK 客户端 */
    private volatile ILinkClient client;

    /** 生命周期状态 */
    private volatile BotLifecycleState state;

    /** 当前二维码（Base64 或文本），无二维码时为 null */
    private volatile String qrCode;

    /** 二维码生成时间 */
    private volatile Instant qrGeneratedAt;

    /** 最后登录时间 */
    private volatile Instant lastLoginAt;

    /** 创建时间 */
    private final Instant createdAt;

    /** 独立线程池（core=2, max=4） */
    private final ExecutorService executor;

    /** 登录流程进行中标记（CAS，防止重复触发登录） */
    private final AtomicBoolean loginInProgress = new AtomicBoolean(false);

    /** 登录串行化锁：旧 client 关闭 + 新 client 构建/登录 原子化 */
    private final ReentrantLock loginLock = new ReentrantLock();

    /**
     * 创建 Bot 实例（仅系统标识，身份登录后注入）。
     * @param botId 系统内部唯一标识
     * @param label 可选的展示标签
     */
    public BotInstance(String botId, String label) {
        this.botId = botId;
        this.label = (label != null && !label.isBlank()) ? label.trim() : null;
        this.state = BotLifecycleState.UNINITIALIZED;
        this.createdAt = Instant.now();
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "bot-" + botId);
            t.setDaemon(true);
            return t;
        });
    }

    /** 便捷构造，无标签 */
    public BotInstance(String botId) {
        this(botId, null);
    }

    // ==================== 身份注入（登录成功后调用） ====================

    /** 登录成功后注入 SDK 返回的真实微信身份 */
    public void setWechatIdentity(String wechatUserId, String wechatBotId, String botToken, String baseUrl) {
        this.wechatUserId = wechatUserId;
        this.wechatBotId = wechatBotId;
        this.botToken = botToken;
        this.baseUrl = baseUrl;
    }

    /** 检查是否已取得真实微信身份 */
    public boolean hasWechatIdentity() {
        return wechatUserId != null && wechatBotId != null;
    }

    /** 比对扫码者是否与历史记录一致 */
    public boolean isSameWechatUser(String newWechatUserId) {
        return wechatUserId != null && wechatUserId.equals(newWechatUserId);
    }

    // ==================== Getters & Setters ====================

    public String getBotId() { return botId; }

    /** @return SDK 返回的扫码者微信 ID，登录成功前为 null */
    public String getWechatUserId() { return wechatUserId; }
    /** @return SDK 返回的微信 Bot ID，登录成功前为 null */
    public String getWechatBotId() { return wechatBotId; }
    /** @return SDK 返回的 Bot Token，登录成功前为 null */
    public String getBotToken() { return botToken; }
    /** @return SDK 返回的 API Base URL，登录成功前为 null */
    public String getBaseUrl() { return baseUrl; }

    /** @deprecated 请使用 {@link #getWechatUserId()}，此方法保留用于向后兼容 */
    @Deprecated
    public String getOwnerUserId() { return wechatUserId; }

    /** 展示标签 */
    public String getLabel() { return label; }

    public ILinkClient getClient() { return client; }
    public void setClient(ILinkClient client) { this.client = client; }
    public BotLifecycleState getState() { return state; }
    public void setState(BotLifecycleState state) { this.state = state; }
    public String getQrCode() { return qrCode; }
    public void setQrCode(String qrCode) { this.qrCode = qrCode; }
    public Instant getQrGeneratedAt() { return qrGeneratedAt; }
    public void setQrGeneratedAt(Instant qrGeneratedAt) { this.qrGeneratedAt = qrGeneratedAt; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public Instant getCreatedAt() { return createdAt; }
    public ExecutorService getExecutor() { return executor; }

    /** 尝试占用登录流程（失败表示已有登录流程进行中） */
    public boolean tryBeginLogin() {
        return loginInProgress.compareAndSet(false, true);
    }

    /** 登录流程结束，释放占用 */
    public void endLogin() {
        loginInProgress.set(false);
    }

    public boolean isLoginInProgress() {
        return loginInProgress.get();
    }

    public ReentrantLock getLoginLock() {
        return loginLock;
    }

    public void shutdown() {
        executor.shutdownNow();
        if (client != null) {
            try { client.close(); } catch (Exception ignored) {}
        }
    }
}
