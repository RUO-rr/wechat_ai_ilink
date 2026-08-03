package io.github.wangyangxu.ailink.service;

import io.github.wangyangxu.ailink.config.BotConfiguration;
import io.github.wangyangxu.ailink.mapper.BotRegistryMapper;
import io.github.wangyangxu.ailink.model.BotInstance;
import io.github.wangyangxu.ailink.model.BotLifecycleState;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.ILinkClientBuilder;
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
    private final ConcurrentHashMap<String, BotInstance> bots = new ConcurrentHashMap<>();

    /** createBot 专用锁：保护 "检查上限 + 放入Map" 的原子性 */
    private final Object createLock = new Object();

    /** 消息回调 */
    private volatile BiConsumer<String, WeixinMessage> messageHandler;

    public BotManager(BotConfiguration botConfig, BotRegistryMapper botRegistryMapper,
                       BotAlertService alertService) {
        this.botConfig = botConfig;
        this.botRegistryMapper = botRegistryMapper;
        this.alertService = alertService;
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
                loginBotAsync(bot.getBotId());
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

    /** 异步登录指定 Bot */
    public void loginBotAsync(String botId) {
        BotInstance bot = bots.get(botId);
        if (bot == null) throw new IllegalArgumentException("Bot 不存在: " + botId);

        bot.setState(BotLifecycleState.UNINITIALIZED);
        bot.getExecutor().submit(() -> doLogin(bot));
    }

    private void doLogin(BotInstance bot) {
        MDC.put("botId", bot.getBotId());
        try {
            ILinkClientBuilder builder = ILinkClient.builder()
                    .onLogin(new BotLoginListener(bot))
                    .onMessage(new BotMessageListener(bot));

            // Q4-B 最小方案：如果 Bot 已有历史 botToken，尝试通过 LoginContext 免扫码恢复
            if (bot.hasWechatIdentity()) {
                LoginContext savedCtx = new LoginContext(
                        bot.getBotToken(), bot.getWechatUserId(),
                        bot.getWechatBotId(), bot.getBaseUrl());
                builder.loginContext(savedCtx);
                log.info("Bot {} 使用历史 LoginContext 尝试免扫码恢复", bot.getBotId());
            }

            ILinkClient client = builder.build();
            bot.setClient(client);

            // 如果 SDK 从 LoginContext 成功恢复了登录态（token 未过期），跳过 QR 流程
            if (client.isLoggedIn()) {
                bot.setState(BotLifecycleState.ONLINE);
                bot.setLastLoginAt(Instant.now());
                log.info("Bot {} 免扫码恢复成功, wechatUserId={}", bot.getBotId(), bot.getWechatUserId());
                return;
            }

            // 正常 QR 登录流程
            bot.setState(BotLifecycleState.QR_READY);
            String qrCode = client.executeLogin();
            bot.setQrCode(qrCode);
            bot.setQrGeneratedAt(Instant.now());
            log.info("Bot {} 二维码已生成，请通过管理页面扫码", bot.getBotId());

            // 等待登录完成（onLoginSuccess 回调会注入身份并持久化）
            try {
                client.getLoginFuture().get(botConfig.getLoginTimeout(), TimeUnit.SECONDS);
                bot.setState(BotLifecycleState.ONLINE);
                bot.setLastLoginAt(Instant.now());
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
    }

    /** 关闭指定 Bot */
    public void shutdownBot(String botId) {
        BotInstance bot = bots.remove(botId);
        if (bot != null) {
            bot.shutdown();
            try { botRegistryMapper.delete(botId); } catch (Exception ignored) {}
            alertService.auditDestroy(botId, bot.getWechatUserId());
            log.info("Bot {} 已关闭", botId);
        }
    }

    /** 断线重连：最多 retryMax 次，间隔 5 秒 */
    private void tryReconnect(BotInstance bot, int retryMax) {
        bot.getExecutor().submit(() -> {
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
        });
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
        BotLoginListener(BotInstance bot) { this.bot = bot; }

        @Override
        public void onLoginSuccess(LoginContext ctx) {
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
            bot.setState(BotLifecycleState.ERROR);
            log.error("Bot {} 登录失败回调", bot.getBotId(), t);
        }
    }

    private class BotMessageListener implements OnMessageListener {
        private final BotInstance bot;
        BotMessageListener(BotInstance bot) { this.bot = bot; }
        @Override
        public void onMessages(List<WeixinMessage> messages) {
            BiConsumer<String, WeixinMessage> handler = messageHandler;
            if (handler == null) {
                log.warn("Bot {} 收到消息但未注册处理器", bot.getBotId());
                return;
            }
            for (WeixinMessage msg : messages) {
                handler.accept(bot.getBotId(), msg);
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
        bots.clear();
    }
}
