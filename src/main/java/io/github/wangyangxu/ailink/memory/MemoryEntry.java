package io.github.wangyangxu.ailink.memory;

import io.github.wangyangxu.ailink.model.AgentMemory;

/**
 * 记忆索引条目 —— 参与检索的最小字段集合。
 * <p>
 * <b>只索引 content</b>：dimension 是给冲突解决与人工排查用的英文标签（answer_style / timezone），
 * 混进检索文本会把标签词变成噪声，反而拉低排序质量。
 */
public record MemoryEntry(long id, String userId, String memoryType, String dimension, String content) {

    public static MemoryEntry from(AgentMemory memory) {
        return new MemoryEntry(
                memory.getId() == null ? -1L : memory.getId(),
                memory.getUserId(),
                memory.getMemoryType(),
                memory.getDimension(),
                memory.getContent());
    }

    /** 注入 prompt 的展示格式，与旧读路径保持一致（便于对比前后行为） */
    public String display() {
        return "[" + memoryType + "/" + dimension + "] " + content;
    }
}