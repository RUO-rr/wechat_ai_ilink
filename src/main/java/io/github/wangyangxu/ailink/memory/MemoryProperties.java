package io.github.wangyangxu.ailink.memory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 记忆检索配置（application.properties 的 {@code memory.*}）。
 * <p>
 * 向量模型不在这里配置：记忆与知识库复用同一个 {@code EmbeddingModel} Bean
 * （见 {@code rag.embedding.*}）—— 两条链路落在同一个向量空间，行为才可预期、可对比。
 */
@Component
public class MemoryProperties {

    @Value("${memory.recall.enabled:true}")
    private boolean recallEnabled;

    @Value("${memory.recall.vector-weight:0.6}")
    private double vectorWeight;

    @Value("${memory.recall.candidate-multiplier:3}")
    private int candidateMultiplier;

    @Value("${memory.recall.recent-fact-guarantee:2}")
    private int recentFactGuarantee;

    @Value("${memory.recall.recent-note-guarantee:1}")
    private int recentNoteGuarantee;

    @Value("${memory.dedupe.enabled:true}")
    private boolean dedupeEnabled;

    @Value("${memory.dedupe.threshold:0.92}")
    private double dedupeThreshold;

    @Value("${memory.index.max-entries-per-user:500}")
    private int maxEntriesPerUser;

    public boolean isRecallEnabled() { return recallEnabled; }
    public double getVectorWeight() { return vectorWeight; }
    public int getCandidateMultiplier() { return candidateMultiplier; }
    public int getRecentFactGuarantee() { return recentFactGuarantee; }
    public int getRecentNoteGuarantee() { return recentNoteGuarantee; }
    public boolean isDedupeEnabled() { return dedupeEnabled; }
    public double getDedupeThreshold() { return dedupeThreshold; }
    public int getMaxEntriesPerUser() { return maxEntriesPerUser; }
}