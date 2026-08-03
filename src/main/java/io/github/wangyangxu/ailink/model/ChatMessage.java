package io.github.wangyangxu.ailink.model;

/**
 * 聊天消息实体，对应 chat_message 表。
 */
public class ChatMessage {

    private Long id;
    private String botId;
    private String userId;
    private String role;
    /** 纯文本内容（user/assistant 简单消息） */
    private String content;
    /** 富结构消息的 JSON 序列化（tool_calls、tool 结果等） */
    private String richContent;
    /** 扩展信息（预留：时区、偏好语言等） */
    private String metadata;
    private String createdAt;

    public ChatMessage() {}

    public ChatMessage(String botId, String userId, String role, String content, String richContent) {
        this.botId = botId;
        this.userId = userId;
        this.role = role;
        this.content = content;
        this.richContent = richContent;
    }

    // ==================== Getters & Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getBotId() { return botId; }
    public void setBotId(String botId) { this.botId = botId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getRichContent() { return richContent; }
    public void setRichContent(String richContent) { this.richContent = richContent; }

    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
