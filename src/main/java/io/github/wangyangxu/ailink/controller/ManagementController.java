package io.github.wangyangxu.ailink.controller;

import io.github.wangyangxu.ailink.config.BotConfiguration;
import io.github.wangyangxu.ailink.model.BotLifecycleState;
import io.github.wangyangxu.ailink.service.BotManager;
import io.github.wangyangxu.ailink.service.MetricsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理 API —— 只暴露只读端点（健康检查 / 指标），
 * 不暴露任何修改记忆或发送消息的接口，防止被恶意调用。
 * 全部端点需通过 {@code X-API-Token} 鉴权。
 */
@RestController
@RequestMapping("/api")
public class ManagementController {

    @Autowired
    private BotManager botManager;

    @Autowired
    private BotConfiguration botConfig;

    @Autowired
    private MetricsService metricsService;

    /** 健康检查：Bot 运行概览 + JVM 运行时长 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        int online = 0;
        int error = 0;
        int disconnected = 0;
        for (var bot : botManager.getAllBots()) {
            BotLifecycleState state = bot.getState();
            if (state == BotLifecycleState.ONLINE) online++;
            else if (state == BotLifecycleState.ERROR) error++;
            else if (state == BotLifecycleState.DISCONNECTED) disconnected++;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "UP");
        m.put("totalBots", botManager.getBotCount());
        m.put("online", online);
        m.put("disconnected", disconnected);
        m.put("error", error);
        m.put("maxBots", botConfig.getMaxBots());
        m.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        return m;
    }

    /** 内存简易计数器：消息量 / 延迟 / FC 轮次 / LLM 调用 / 记忆任务等 */
    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return metricsService.snapshot();
    }
}
