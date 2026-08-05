package io.github.wangyangxu.ailink.model;

/**
 * 长期记忆实体，对应 agent_memory 表。
 * <ul>
 *   <li>fact / preference：每轮 LLM 自动提取，带冲突解决（supersede）</li>
 *   <li>summary：每 N 轮滚动摘要，supersede 链保留前身</li>
 *   <li>note：remember 工具手动写入，永不 supersede，仅手动软删除</li>
 * </ul>
 */
public class AgentMemory {

    public static final String TYPE_FACT = "fact";
    public static final String TYPE_PREFERENCE = "preference";
    public static final String TYPE_SUMMARY = "summary";
    public static final String TYPE_NOTE = "note";

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_SUPERSEDED = "superseded";
    public static final String STATUS_DELETED = "deleted";

    public static final String DIM_SUMMARY = "history";
    public static final String DIM_NOTE = "user_note";

    private Long id;
    private String userId;
    private String memoryType;
    private String dimension;
    private String content;
    private Long sourceMessageId;
    private String status;
    private Long supersedesId;
    private String createdAt;
    private String updatedAt;

    public AgentMemory() {}

    public AgentMemory(String userId, String memoryType, String dimension, String content,
                       Long sourceMessageId, String status, Long supersedesId) {
        this.userId = userId;
        this.memoryType = memoryType;
        this.dimension = dimension;
        this.content = content;
        this.sourceMessageId = sourceMessageId;
        this.status = status;
        this.supersedesId = supersedesId;
    }

    // ==================== Getters & Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getMemoryType() { return memoryType; }
    public void setMemoryType(String memoryType) { this.memoryType = memoryType; }

    public String getDimension() { return dimension; }
    public void setDimension(String dimension) { this.dimension = dimension; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Long getSourceMessageId() { return sourceMessageId; }
    public void setSourceMessageId(Long sourceMessageId) { this.sourceMessageId = sourceMessageId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Long getSupersedesId() { return supersedesId; }
    public void setSupersedesId(Long supersedesId) { this.supersedesId = supersedesId; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
