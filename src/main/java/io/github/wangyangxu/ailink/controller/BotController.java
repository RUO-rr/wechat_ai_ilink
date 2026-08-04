package io.github.wangyangxu.ailink.controller;

import io.github.wangyangxu.ailink.config.BotConfiguration;
import io.github.wangyangxu.ailink.model.BotInstance;
import io.github.wangyangxu.ailink.model.BotLifecycleState;
import io.github.wangyangxu.ailink.service.BotManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Bot 管理 REST API —— 创建、查询、刷新、销毁 Bot。
 */
@RestController
@RequestMapping("/bot")
public class BotController {

    @Autowired
    private BotManager botManager;
    @Autowired
    private BotConfiguration botConfig;

    /**
     * 创建并异步登录新 Bot。
     * 不接受 userId 参数 —— 身份在扫码后由 SDK 注入，以微信平台认证结果为信任根。
     * @param label 可选的展示标签（用于管理界面区分多个 Bot）
     */
    @PostMapping("/create")
    public Map<String, Object> createBot(@RequestParam(required = false) String label) {
        try {
            String botId = botManager.createBot(label);
            botManager.loginBotAsync(botId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("botId", botId);
            result.put("message", "Bot 已创建，请扫码登录");
            return result;
        } catch (Exception e) {
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    /** 获取所有 Bot 状态列表 */
    @GetMapping("/bots")
    public List<Map<String, Object>> listBots() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (BotInstance bot : botManager.getAllBots()) {
            result.add(botSummary(bot));
        }
        return result;
    }

    /** 获取单个 Bot 详情（含二维码） */
    @GetMapping("/{botId}/status")
    public Map<String, Object> getBotStatus(@PathVariable String botId) {
        BotInstance bot = botManager.getBot(botId);
        if (bot == null) return Map.of("error", "Bot 不存在: " + botId);
        Map<String, Object> info = botSummary(bot);
        info.put("qrCode", bot.getQrCode());
        if (bot.getQrGeneratedAt() != null) {
            info.put("qrGeneratedAt", bot.getQrGeneratedAt().toString());
        }
        return info;
    }

    /** 获取 Bot 当前二维码 */
    @GetMapping("/{botId}/qr")
    public Map<String, Object> getQrCode(@PathVariable String botId) {
        BotInstance bot = botManager.getBot(botId);
        if (bot == null) return Map.of("error", "Bot 不存在: " + botId);
        String qr = bot.getQrCode();
        if (qr == null) return Map.of("error", "二维码未生成或已失效");
        return Map.of("botId", botId, "qrCode", qr);
    }

    /** 强制刷新二维码 */
    @PostMapping("/{botId}/qr/refresh")
    public Map<String, Object> refreshQr(@PathVariable String botId) {
        BotInstance bot = botManager.getBot(botId);
        if (bot == null) return Map.of("success", false, "error", "Bot 不存在: " + botId);
        try {
            // 强制重新扫码：忽略保存的 LoginContext，跳过免扫码恢复，直接生成新二维码
            botManager.loginBotAsync(botId, true);
            return Map.of("success", true, "botId", botId, "message", "正在强制重新扫码，请稍候");
        } catch (IllegalStateException e) {
            // 并发保护：已有登录流程进行中，提示用户稍候
            return Map.of("success", false, "botId", botId, "error", e.getMessage());
        }
    }

    /** 手动销毁 Bot */
    @DeleteMapping("/{botId}")
    public Map<String, Object> deleteBot(@PathVariable String botId) {
        botManager.shutdownBot(botId);
        return Map.of("success", true, "botId", botId, "message", "Bot 已销毁");
    }

    /** Bot 健康检查 */
    @GetMapping("/health/{botId}")
    public Map<String, Object> healthCheck(@PathVariable String botId) {
        BotInstance bot = botManager.getBot(botId);
        if (bot == null) return Map.of("error", "Bot 不存在: " + botId);

        Map<String, Object> health = new LinkedHashMap<>();
        health.put("botId", botId);
        health.put("state", bot.getState().name());
        health.put("connected", bot.getState() == BotLifecycleState.ONLINE);
        health.put("uptimeMinutes", java.time.Duration.between(bot.getCreatedAt(), java.time.Instant.now()).toMinutes());
        if (bot.getLastLoginAt() != null)
            health.put("lastLoginAt", bot.getLastLoginAt().toString());
        return health;
    }

    /** 全局健康概览 */
    @GetMapping("/health")
    public Map<String, Object> globalHealth() {
        int online = 0, error = 0;
        for (BotInstance bot : botManager.getAllBots()) {
            if (bot.getState() == BotLifecycleState.ONLINE) online++;
            else if (bot.getState() == BotLifecycleState.ERROR) error++;
        }
        return Map.of(
                "totalBots", botManager.getBotCount(),
                "online", online,
                "error", error,
                "maxBots", botConfig.getMaxBots()
        );
    }

    private Map<String, Object> botSummary(BotInstance bot) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("botId", bot.getBotId());
        if (bot.getLabel() != null) m.put("label", bot.getLabel());
        m.put("wechatUserId", bot.getWechatUserId()); // 扫码前为 null
        m.put("wechatBotId", bot.getWechatBotId());     // 扫码前为 null
        m.put("state", bot.getState().name());
        if (bot.getLastLoginAt() != null) m.put("lastLoginAt", bot.getLastLoginAt().toString());
        m.put("createdAt", bot.getCreatedAt().toString());
        return m;
    }
}
