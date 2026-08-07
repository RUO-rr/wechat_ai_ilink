package io.github.wangyangxu.ailink.service;

import io.github.wangyangxu.ailink.config.BotConfiguration;
import io.github.wangyangxu.ailink.mapper.BotRegistryMapper;
import io.github.wangyangxu.ailink.model.BotInstance;
import io.github.wangyangxu.ailink.model.BotLifecycleState;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.ILinkClientBuilder;
import com.github.wechat.ilink.sdk.core.listener.OnDisconnectListener;
import com.github.wechat.ilink.sdk.core.listener.OnHeartbeatListener;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;

/**
 * Bot 生命周期管理器 —— 核心组件。
 * 管理所有 BotInstance 的创建、登录、状态追踪、关闭。
 * ConcurrentHashMap 保证并发安全。
 *
 * <h3>身份模型</h3>
 * Bot 创建时不绑定任何用户身份。扫码登录后，SDK 返回 {@link LoginContext}，
 * 其中包含扫码者的真实微信 ID（userId）和微信分配的 Bot ID（botId）。
 * 系统以此作为信任根，写入 BotInstance 并持久化到 bot_registry。
 */
@Component
public class BotManager {

    private static final Logger log = LoggerFactory.getLogger(BotManager.class);

    /** 系统 Bot ID 的随机字符集 */
    private static final String ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int ID_RANDOM_LEN = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BotConfiguration botConfig;
    private final BotRegistryMapper botRegistryMapper;
    private final BotAlertService alertService;
    private final MetricsService metricsService;
    private final ConcurrentHashMap<String, BotInstance> bots = new ConcurrentHashMap<>();

    /** createBot 专用锁：保护 "检查上限 + 放入Map" 的原子性 */
    private final Object createLock = new Object();

    /** 消息回调 */
    private volatile BiConsumer<String, WeixinMessage> messageHandler;

    /** 心跳连续失败阈值：连续 N 次失败触发降级恢复（心跳间隔 3s，10 次 ≈ 30s 失败窗口） */
    private static final int HEARTBEAT_FAIL_THRESHOLD = 10;

    /** 心跳连续失败计数（botId → 次数），onHeartbeatSuccess 时清零 */
    private final ConcurrentHashMap<String, AtomicInteger> heartbeatFailCount = new ConcurrentHashMap<>();

    public BotManager(BotConfiguration botConfig, BotRegistryMapper botRegistryMapper,
                       BotAlertService alertService, MetricsService metricsService) {
        this.botConfig = botConfig;
        this.botRegistryMapper = botRegistryMapper;
        this.alertService = alertService;
        this.metricsService = metricsService;
    }

    // ==================== 启动恢复 ====================

    /** 启动时从 DB 恢复所有 Bot 并自动重新登录 */
    @PostConstruct
    public void recoverFromDb() {
        try {
            List<Map<String, Object>> rows = botRegistryMapper.findAll();
            if (rows.isEmpty()) {
                log.info("bot_registry 表为空，无需恢复");
                return;
            }
            log.info("从 DB 恢复 {} 个 Bot...", rows.size());
            for (Map<String, Object> row : rows) {
                String botId = (String) row.get("botId");
                String label = (String) row.get("label");
                String wechatUserId = (String) row.get("wechatUserId");
                String wechatBotId = (String) row.get("wechatBotId");
                String botToken = (String) row.get("botToken");
                String baseUrl = (String) row.get("baseUrl");

                BotInstance bot = new BotInstance(botId, label);
                // 注入历史身份（如有）
                if (wechatUserId != null) {
                    bot.setWechatIdentity(wechatUserId, wechatBotId, botToken, baseUrl);
                }
                bots.put(botId, bot);
                log.info("恢复 Bot: botId={}, wechatUserId={}, label={}", botId, wechatUserId, label);
            }
            // 恢复后自动重新登录所有 Bot
            for (BotInstance bot : bots.values()) {
                try {
                    loginBotAsync(bot.getBotId());
                } catch (Exception e) {
                    log.error("恢复 Bot {} 登录失败", bot.getBotId(), e);
                }
            }
        } catch (Exception e) {
            log.error("从 DB 恢复 Bot 失败", e);
        }
    }

    /** 注册消息处理器（MainController 初始化时调用） */
    public void registerMessageHandler(BiConsumer<String, WeixinMessage> handler) {
        this.messageHandler = handler;
    }

    // ==================== Bot 生命周期管理 ====================

    /**
     * 生成系统内部 Bot ID：{@code bot_} + 8 位随机字母数字。
     * 碰撞概率：36^8 ≈ 2.8 万亿分之一，可忽略。
     */
    private String generateBotId() {
        StringBuilder sb = new StringBuilder("bot_");
        for (int i = 0; i < ID_RANDOM_LEN; i++) {
            sb.append(ID_CHARS.charAt(RANDOM.nextInt(ID_CHARS.length())));
        }
        return sb.toString();
    }

    /**
     * 创建新的 Bot 实例（不绑定身份，身份在扫码后由 SDK 注入）。
     * @param label 可选的展示标签（null 表示无标签）
     * @return 系统生成的 botId
     * @throws IllegalStateException 已达最大 Bot 数
     */
    public String createBot(String label) {
        // ②③ 锁外：不依赖共享状态，无需互斥
        String botId = generateBotId();
        BotInstance bot = new BotInstance(botId, label);

        // ①④ 锁内：check-then-act 必须原子化
        synchronized (createLock) {
            if (bots.size() >= botConfig.getMaxBots()) {
                bot.shutdown(); // 创建失败，回收线程池
                throw new IllegalStateException("Bot 数量已达上限(" + botConfig.getMaxBots() + ")，无法创建新 Bot");
            }
            bots.put(botId, bot);
        }

        // ⑤⑥ 锁外：日志和审计不影响正确性，避免延长锁持有时间
        log.info("创建 Bot 实例: botId={}, label={}", botId, label);
        alertService.auditCreate(botId, null);
        return botId;
    }

    /** 异步登录指定 Bot（默认尝试免扫码恢复） */
    public void loginBotAsync(String botId) {
        loginBotAsync(botId, false);
    }

    /**
     * 异步登录指定 Bot —— 所有登录/恢复场景的统一入口（创建、启动恢复、强制刷新、断线/心跳降级）。
     *
     * @param forceNewQr true=强制重新扫码：忽略保存的 LoginContext，跳过免扫码恢复，直接生成新二维码
     * @throws IllegalStateException 该 Bot 已有登录流程进行中（并发保护，调用方应提示用户稍候）
     */
    public void loginBotAsync(String botId, boolean forceNewQr) {
        BotInstance bot = bots.get(botId);
        if (bot == null) throw new IllegalArgumentException("Bot 不存在: " + botId);

        if (!bot.tryBeginLogin()) {
            throw new IllegalStateException("该 Bot 正在登录中，请稍候重试");
        }
        bot.getExecutor().submit(() -> {
            try {
                doLogin(bot, forceNewQr);
            } finally {
                bot.endLogin();
            }
        });
    }

    /**
     * 登录核心流程。持有 Bot 级 ReentrantLock，保证
     * "旧 client 关闭 + 新 client 构建/登录" 原子化（同一锁保护区段）。
     */
    private void doLogin(BotInstance bot, boolean forceNewQr) {
        ReentrantLock loginLock = bot.getLoginLock();
        loginLock.lock();
        try {
            MDC.put("botId", bot.getBotId());
            try {
                // ① 关闭旧 client：先置空引用，回调过滤立即生效，防止旧 client 关闭瞬间的回调污染新登录
                ILinkClient oldClient = bot.getClient();
                if (oldClient != null) {
                    log.info("Bot {} 关闭旧客户端，准备重建连接", bot.getBotId());
                    bot.setClient(null);
                    closeClientQuietly(oldClient);
                }

                bot.setState(BotLifecycleState.UNINITIALIZED);

                // ② 注册全部监听器（登录 / 消息 / 断线 / 心跳），构建后绑定 client 实例用于回调过滤
                BotLoginListener loginListener = new BotLoginListener(bot);
                BotMessageListener messageListener = new BotMessageListener(bot);
                BotDisconnectListener disconnectListener = new BotDisconnectListener(bot);
                BotHeartbeatListener heartbeatListener = new BotHeartbeatListener(bot);

                ILinkClientBuilder builder = ILinkClient.builder()
                        .onLogin(loginListener)
                        .onMessage(messageListener)
                        .onDisconnect(disconnectListener)
                        .onHeartbeat(heartbeatListener);

                // ③ 免扫码恢复仅在非强制刷新时尝试
                if (!forceNewQr && bot.hasWechatIdentity()) {
                    LoginContext savedCtx = new LoginContext(
                            bot.getBotToken(), bot.getWechatUserId(),
                            bot.getWechatBotId(), bot.getBaseUrl());
                    builder.loginContext(savedCtx);
                    log.info("Bot {} 使用历史 LoginContext 尝试免扫码恢复", bot.getBotId());
                }

                ILinkClient client = builder.build();
                loginListener.bind(client);
                messageListener.bind(client);
                disconnectListener.bind(client);
                heartbeatListener.bind(client);
                bot.setClient(client);

                // ④ 免扫码恢复成功 → 直接上线（强制刷新路径跳过此判断，直接进二维码流程）
                if (!forceNewQr && client.isLoggedIn()) {
                    bot.setState(BotLifecycleState.ONLINE);
                    bot.setLastLoginAt(Instant.now());
                    resetHeartbeatFailCount(bot.getBotId());
                    log.info("Bot {} 免扫码恢复成功, wechatUserId={}", bot.getBotId(), bot.getWechatUserId());
                    return;
                }

                // ⑤ 二维码登录流程
                bot.setState(BotLifecycleState.QR_READY);
                String qrCode = client.executeLogin();
                bot.setQrCode(qrCode);
                bot.setQrGeneratedAt(Instant.now());
                log.info("Bot {} 二维码已生成，请通过管理页面扫码", bot.getBotId());

                try {
                    client.getLoginFuture().get(botConfig.getLoginTimeout(), TimeUnit.SECONDS);
                    bot.setState(BotLifecycleState.ONLINE);
                    bot.setLastLoginAt(Instant.now());
                    resetHeartbeatFailCount(bot.getBotId());
                    log.info("Bot {} 登录成功, wechatUserId={}", bot.getBotId(), bot.getWechatUserId());
                    alertService.auditLogin(bot.getBotId(), bot.getWechatUserId(), true, "OK");
                } catch (TimeoutException e) {
                    bot.setState(BotLifecycleState.QR_EXPIRED);
                    log.warn("Bot {} 登录超时({}秒)", bot.getBotId(), botConfig.getLoginTimeout());
                    alertService.auditLogin(bot.getBotId(), bot.getWechatUserId(), false, "超时");
                    tryReconnect(bot, 3);
                }
            } catch (Exception e) {
                bot.setState(BotLifecycleState.ERROR);
                log.error("Bot {} 登录失败", bot.getBotId(), e);
                alertService.auditLogin(bot.getBotId(), bot.getWechatUserId(), false, e.getMessage());
            } finally {
                MDC.remove("botId");
            }
        } finally {
            loginLock.unlock();
        }
    }

    /** 关闭指定 Bot */
    public void shutdownBot(String botId) {
        BotInstance bot = bots.remove(botId);
        if (bot != null) {
            bot.shutdown();
            heartbeatFailCount.remove(botId);
            try { botRegistryMapper.delete(botId); } catch (Exception ignored) {}
            alertService.auditDestroy(botId, bot.getWechatUserId());
            log.info("Bot {} 已关闭", botId);
        }
    }

    /**
     * 断线重连：最多 retryMax 次，间隔 5 秒。
     * 同步执行（由 doLogin 在同一锁/占用标记内调用），避免与新的登录流程并发打架。
     */
    private void tryReconnect(BotInstance bot, int retryMax) {
        for (int i = 1; i <= retryMax; i++) {
            try {
                log.info("Bot {} 重连尝试 {}/{}", bot.getBotId(), i, retryMax);
                Thread.sleep(5000);

                // 读一次、存局部变量，后续全部用它，避免多次 volatile 读到不同值
                ILinkClient client = bot.getClient();
                if (client == null) {
                    log.warn("Bot {} client 已为空（可能已被销毁），停止重连", bot.getBotId());
                    return;
                }

                bot.setState(BotLifecycleState.UNINITIALIZED);
                String qr = client.executeLogin();
                bot.setQrCode(qr);
                bot.setQrGeneratedAt(Instant.now());
                bot.setState(BotLifecycleState.QR_READY);
                log.info("Bot {} 重连二维码已生成(尝试 {}/{})", bot.getBotId(), i, retryMax);

                client.getLoginFuture().get(botConfig.getLoginTimeout(), TimeUnit.SECONDS);
                bot.setState(BotLifecycleState.ONLINE);
                bot.setLastLoginAt(Instant.now());
                resetHeartbeatFailCount(bot.getBotId());
                log.info("Bot {} 重连成功", bot.getBotId());
                return;
            } catch (InterruptedException e) {
                // shutdownNow() 中断了线程，说明 Bot 已被销毁，直接退出
                Thread.currentThread().interrupt();
                log.info("Bot {} 重连线程被中断，退出", bot.getBotId());
                return;
            } catch (Exception e) {
                log.warn("Bot {} 重连失败 {}/{}: {}", bot.getBotId(), i, retryMax, e.getMessage());
            }
        }
        bot.setState(BotLifecycleState.ERROR);
        alertService.alertReconnectFailed(bot.getBotId());
        log.error("Bot {} 重连 {} 次全部失败", bot.getBotId(), retryMax);
    }

    /** 关闭 client（SDK 无超时重载，本地操作 + 回调过滤覆盖竞态） */
    private void closeClientQuietly(ILinkClient client) {
        try {
            client.close();
        } catch (Exception e) {
            log.warn("关闭旧 client 异常: {}", e.getMessage());
        }
    }

    /**
     * 降级恢复（SDK 会话丢失触发）：标记 DISCONNECTED → 免扫码恢复。
     * 复用统一登录入口 loginBotAsync(botId, false)，并发保护由 CAS 标志承担；
     * 若免扫码恢复失败，doLogin 内部会自然进入二维码流程，管理员扫码即可恢复。
     */
    private void triggerDegradation(BotInstance bot, String reason) {
        String botId = bot.getBotId();
        BotLifecycleState state = bot.getState();
        // 仅在 ONLINE / DISCONNECTED 时触发，避免在扫码等待等阶段误触发
        if (state != BotLifecycleState.ONLINE && state != BotLifecycleState.DISCONNECTED) {
            log.info("Bot {} 状态 {}，跳过降级触发(reason={})", botId, state, reason);
            return;
        }
        bot.setState(BotLifecycleState.DISCONNECTED);
        alertService.alertSessionLost(botId, reason);
        metricsService.recordSessionLost();
        log.warn("Bot {} 触发降级恢复 reason={}", botId, reason);
        try {
            loginBotAsync(botId, false);
        } catch (IllegalStateException e) {
            log.warn("Bot {} 降级恢复被跳过（登录流程已在进行中）: {}", botId, e.getMessage());
        }
    }

    private void resetHeartbeatFailCount(String botId) {
        heartbeatFailCount.computeIfAbsent(botId, k -> new AtomicInteger()).set(0);
    }

    // ==================== 查询 ====================

    public BotInstance getBot(String botId) { return bots.get(botId); }

    /** 通过微信真实 ID 查找 Bot（扫码者维度） */
    public BotInstance getBotByWechatUserId(String wechatUserId) {
        if (wechatUserId == null) return null;
        for (BotInstance bot : bots.values()) {
            if (wechatUserId.equals(bot.getWechatUserId())) return bot;
        }
        return null;
    }

    /** @deprecated 请使用 {@link #getBotByWechatUserId(String)} */
    @Deprecated
    public BotInstance getBotByUserId(String userId) {
        return getBotByWechatUserId(userId);
    }

    public Collection<BotInstance> getAllBots() {
        return Collections.unmodifiableCollection(bots.values());
    }

    public int getBotCount() { return bots.size(); }

    // ==================== 消息监听器（闭包注入 botId） ====================

    private class BotLoginListener implements OnLoginListener {
        private final BotInstance bot;
        private volatile ILinkClient boundClient;

        BotLoginListener(BotInstance bot) { this.bot = bot; }

        void bind(ILinkClient client) { this.boundClient = client; }

        /** 回调过滤：仅处理当前 client 的回调，忽略旧 client 关闭瞬间的残留回调 */
        boolean isCurrent() {
            return boundClient != null && boundClient == bot.getClient();
        }

        @Override
        public void onLoginSuccess(LoginContext ctx) {
            if (!isCurrent()) return;
            // 注入 SDK 返回的真实微信身份
            String newWechatUserId = ctx.getUserId();
            String newWechatBotId = ctx.getBotId();

            // Q3-B 宽松模式：检测身份不匹配 → 接受新身份 + 告警
            if (bot.hasWechatIdentity() && !bot.isSameWechatUser(newWechatUserId)) {
                log.warn("Bot {} 身份变更: wechatUserId {} → {}",
                        bot.getBotId(), bot.getWechatUserId(), newWechatUserId);
                alertService.alertIdentityChanged(bot.getBotId(),
                        bot.getWechatUserId(), newWechatUserId);
            }

            bot.setWechatIdentity(newWechatUserId, newWechatBotId,
                    ctx.getBotToken(), ctx.getBaseUrl());

            // 持久化到 bot_registry
            try {
                botRegistryMapper.upsert(bot.getBotId(), bot.getLabel(),
                        bot.getWechatUserId(), bot.getWechatBotId(),
                        bot.getBotToken(), bot.getBaseUrl());
                log.info("Bot {} 身份已持久化: wechatUserId={}, wechatBotId={}",
                        bot.getBotId(), bot.getWechatUserId(), bot.getWechatBotId());
            } catch (Exception e) {
                log.error("Bot {} 持久化失败", bot.getBotId(), e);
            }

            log.info("Bot {} 登录成功回调, SDK botId={}, wechatUserId={}",
                    bot.getBotId(), ctx.getBotId(), ctx.getUserId());
        }

        @Override
        public void onLoginFailure(Throwable t) {
            if (!isCurrent()) return;
            bot.setState(BotLifecycleState.ERROR);
            log.error("Bot {} 登录失败回调", bot.getBotId(), t);
        }
    }

    private class BotMessageListener implements OnMessageListener {
        private final BotInstance bot;
        private volatile ILinkClient boundClient;

        BotMessageListener(BotInstance bot) { this.bot = bot; }

        void bind(ILinkClient client) { this.boundClient = client; }

        boolean isCurrent() {
            return boundClient != null && boundClient == bot.getClient();
        }

        @Override
        public void onMessages(List<WeixinMessage> messages) {
            if (!isCurrent()) return;
            BiConsumer<String, WeixinMessage> handler = messageHandler;
            if (handler == null) {
                log.warn("Bot {} 收到消息但未注册处理器", bot.getBotId());
                return;
            }
            for (WeixinMessage msg : messages) {
                // 异步化：提交到 per-bot 消息执行器，轮询线程立即返回，
                // 避免 LLM 处理耗时拖累下一轮消息轮询（心跳）
                WeixinMessage m = msg;
                if (!bot.submitMessage(() -> handler.accept(bot.getBotId(), m))) {
                    metricsService.recordQueueDrop();
                }
            }
        }
    }

    /**
     * SDK 底层断线/重连回调 —— 会话丢失的可靠信号来源。
     * onReconnectFailed 表示 SDK 自动重连彻底失败（token 很可能已死），触发降级恢复。
     */
    private class BotDisconnectListener implements OnDisconnectListener {
        private final BotInstance bot;
        private volatile ILinkClient boundClient;

        BotDisconnectListener(BotInstance bot) { this.bot = bot; }

        void bind(ILinkClient client) { this.boundClient = client; }

        boolean isCurrent() {
            return boundClient != null && boundClient == bot.getClient();
        }

        @Override
        public void onDisconnect(Throwable t) {
            if (!isCurrent()) return;
            bot.setState(BotLifecycleState.DISCONNECTED);
            log.warn("Bot {} 连接断开: {}", bot.getBotId(), t != null ? t.getMessage() : "unknown");
        }

        @Override
        public void onReconnectStart(int attempt) {
            if (!isCurrent()) return;
            log.info("Bot {} SDK 自动重连开始(第{}次)", bot.getBotId(), attempt);
        }

        @Override
        public void onReconnectSuccess() {
            if (!isCurrent()) return;
            bot.setState(BotLifecycleState.ONLINE);
            resetHeartbeatFailCount(bot.getBotId());
            log.info("Bot {} SDK 自动重连成功", bot.getBotId());
        }

        @Override
        public void onReconnectFailed(Throwable t) {
            if (!isCurrent()) return;
            log.error("Bot {} SDK 自动重连失败，触发降级恢复: {}",
                    bot.getBotId(), t != null ? t.getMessage() : "");
            triggerDegradation(bot, "reconnect_failed");
        }
    }

    /**
     * SDK 底层心跳回调 —— 连接存活的辅助信号。
     * 连续 {@link #HEARTBEAT_FAIL_THRESHOLD} 次失败视为会话丢失，触发降级恢复。
     */
    private class BotHeartbeatListener implements OnHeartbeatListener {
        private final BotInstance bot;
        private volatile ILinkClient boundClient;

        BotHeartbeatListener(BotInstance bot) { this.bot = bot; }

        void bind(ILinkClient client) { this.boundClient = client; }

        boolean isCurrent() {
            return boundClient != null && boundClient == bot.getClient();
        }

        @Override
        public void onHeartbeatSuccess() {
            if (!isCurrent()) return;
            AtomicInteger count = heartbeatFailCount.computeIfAbsent(bot.getBotId(), k -> new AtomicInteger());
            int prev = count.getAndSet(0);
            if (prev >= 1) {
                log.info("Bot {} 心跳恢复，失败计数清零", bot.getBotId());
            }
        }

        @Override
        public void onHeartbeatFailure(Throwable t) {
            if (!isCurrent()) return;
            AtomicInteger count = heartbeatFailCount.computeIfAbsent(bot.getBotId(), k -> new AtomicInteger());
            int fails = count.incrementAndGet();
            log.warn("Bot {} 心跳失败 {}/{}: {}",
                    bot.getBotId(), fails, HEARTBEAT_FAIL_THRESHOLD,
                    t != null ? t.getMessage() : "");
            if (fails >= HEARTBEAT_FAIL_THRESHOLD) {
                count.set(0); // 防止重复触发
                triggerDegradation(bot, "heartbeat_failed");
            }
        }
    }

    // ==================== 优雅关闭 ====================

    @PreDestroy
    public void shutdownAll() {
        log.info("关闭所有 Bot (共 {} 个)...", bots.size());
        CompletableFuture<?>[] futures = bots.values().stream()
                .map(bot -> CompletableFuture.runAsync(bot::shutdown))
                .toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(futures).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Bot 关闭超时", e);
        }
        heartbeatFailCount.clear();
        bots.clear();
    }
}
