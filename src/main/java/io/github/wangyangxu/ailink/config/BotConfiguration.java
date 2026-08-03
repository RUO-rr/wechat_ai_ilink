package io.github.wangyangxu.ailink.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bot 全局配置 —— 不预配 Bot 列表，只配运行时参数。
 */
@Component
@ConfigurationProperties(prefix = "bot")
public class BotConfiguration {

    /** 最大 Bot 数量 */
    private int maxBots = 10;

    /** 二维码刷新间隔（秒） */
    private int qrRefreshInterval = 60;

    /** 二维码超时（秒） */
    private int qrTimeout = 120;

    /** 登录超时（秒） */
    private int loginTimeout = 180;

    // ==================== Getters & Setters ====================

    public int getMaxBots() { return maxBots; }
    public void setMaxBots(int maxBots) { this.maxBots = maxBots; }
    public int getQrRefreshInterval() { return qrRefreshInterval; }
    public void setQrRefreshInterval(int qrRefreshInterval) { this.qrRefreshInterval = qrRefreshInterval; }
    public int getQrTimeout() { return qrTimeout; }
    public void setQrTimeout(int qrTimeout) { this.qrTimeout = qrTimeout; }
    public int getLoginTimeout() { return loginTimeout; }
    public void setLoginTimeout(int loginTimeout) { this.loginTimeout = loginTimeout; }
}
