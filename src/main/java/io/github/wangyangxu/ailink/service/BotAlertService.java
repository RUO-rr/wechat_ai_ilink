package io.github.wangyangxu.ailink.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Bot 告警服务 —— 集中输出结构化的告警/审计日志。
 * 后续可扩展为邮件/钉钉通知。
 */
@Service
public class BotAlertService {

    /** 独立 logger，方便日志采集系统过滤 */
    private static final Logger alertLog = LoggerFactory.getLogger("BOT_ALERT");
    private static final Logger auditLog = LoggerFactory.getLogger("BOT_AUDIT");

    // ==================== 审计日志 ====================

    public void auditCreate(String botId, String userId) {
        auditLog.info("action=create botId={} userId={} timestamp={}", botId, userId, Instant.now());
    }

    public void auditLogin(String botId, String userId, boolean success, String detail) {
        auditLog.info("action=login botId={} userId={} success={} detail={}", botId, userId, success, detail);
    }

    public void auditDestroy(String botId, String userId) {
        auditLog.info("action=destroy botId={} userId={} timestamp={}", botId, userId, Instant.now());
    }

    public void auditStateChange(String botId, String from, String to) {
        auditLog.info("action=state_change botId={} from={} to={}", botId, from, to);
    }

    // ==================== 告警日志 ====================

    public void alertLoginFailed(String botId, String userId, int attempts) {
        alertLog.warn("type=LOGIN_FAILED botId={} userId={} attempts={} severity=WARN", botId, userId, attempts);
        if (attempts >= 3) {
            alertLog.error("type=LOGIN_CRITICAL botId={} userId={} attempts={} severity=CRITICAL message=\"Bot 连续登录失败3次\"", botId, userId, attempts);
        }
    }

    public void alertReconnectFailed(String botId) {
        alertLog.error("type=RECONNECT_FAILED botId={} severity=CRITICAL message=\"Bot 重连全部失败，已进入 ERROR 状态\"", botId);
    }

    public void alertOffline(String botId, long offlineMinutes) {
        alertLog.warn("type=OFFLINE botId={} offlineMinutes={} severity=WARN", botId, offlineMinutes);
        if (offlineMinutes >= 5) {
            alertLog.error("type=OFFLINE_CRITICAL botId={} offlineMinutes={} severity=CRITICAL message=\"Bot 离线超过5分钟\"", botId, offlineMinutes);
        }
    }

    /** 身份变更告警：扫码者与历史绑定者不一致时触发 */
    public void alertIdentityChanged(String botId, String oldWechatUserId, String newWechatUserId) {
        alertLog.warn("type=IDENTITY_CHANGED botId={} oldWechatUserId={} newWechatUserId={} severity=WARN message=\"Bot 扫码身份发生变更\"",
                botId, oldWechatUserId, newWechatUserId);
    }
}
