package io.github.wangyangxu.ailink.model;

/**
 * Bot 生命周期状态枚举。
 */
public enum BotLifecycleState {
    /** 刚创建，尚未初始化 */
    UNINITIALIZED,
    /** 二维码已生成，等待用户扫码 */
    QR_READY,
    /** 用户扫码成功，正在登录中 */
    LOGGING_IN,
    /** 已在线，可正常收发消息 */
    ONLINE,
    /** 二维码超时未扫 */
    QR_EXPIRED,
    /** SDK 连接断开，尝试自动重连 */
    DISCONNECTED,
    /** 手动停用 */
    OFFLINE,
    /** 异常状态 */
    ERROR
}
