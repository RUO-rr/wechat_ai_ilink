package io.github.wangyangxu.ailink.memory;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.github.wangyangxu.ailink.mapper.AgentMemoryMapper;
import io.github.wangyangxu.ailink.model.AgentMemory;
import io.github.wangyangxu.ailink.rag.EmbeddingCodec;
import io.github.wangyangxu.ailink.rag.HashingEmbeddingModel;
import io.github.wangyangxu.ailink.rag.HybridIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 长期记忆内存索引 —— 按用户隔离的 {@link HybridIndex}（向量 ∥ BM25），与知识库共用同一套打分内核。
 * <ul>
 *   <li><b>按需装载</b>：首次访问某用户时才从库里拉取他的 active 记忆建索引 ——
 *       记忆是「每人几十条」的量级，按需建索引比全局预热更划算，也不会把启动时间绑在向量模型上；</li>
 *   <li><b>写入同步</b>：索引已装载时，新增 / 取代 / 删除立刻反映到索引；未装载则不动它 ——
 *       库里已是最新，下一次装载自然会带上（避免为写一条记忆把整库拉起来）；</li>
 *   <li><b>降级</b>：向量化失败只让该批条目退化为关键词通道；库不可用返回 false，
 *       调用方退回 recency 注入 —— 检索可以退化，回复不能中断。</li>
 * </ul>
 */
@Component
public class MemoryIndex {

    private static final Logger log = LoggerFactory.getLogger(MemoryIndex.class);

    /** 单次向量化的条目数上限，与灌库侧保持一致（批量接口对单次请求条数有限制） */
    private static final int EMBED_BATCH_SIZE = 10;

    private final ConcurrentHashMap<String, HybridIndex<MemoryEntry>> byUser = new ConcurrentHashMap<>();

    private final AgentMemoryMapper memoryMapper;
    private final EmbeddingModel embeddingModel;
    private final MemoryProperties props;

    public MemoryIndex(AgentMemoryMapper memoryMapper,
                       EmbeddingModel ragEmbeddingModel,
                       MemoryProperties props) {
        this.memoryMapper = memoryMapper;
        this.embeddingModel = ragEmbeddingModel;
        this.props = props;
    }

    // ==================== 装载 ====================

    /** 是否已为该用户建好索引（false 表示读路径需要退回 recency 注入） */
    public boolean isLoaded(String userId) {
        return userId != null && byUser.containsKey(userId);
    }

    /**
     * 幂等装载：已装载直接返回 true；装载失败返回 false 且不缓存失败状态（下一条消息会重试）。
     */
    public boolean ensureLoaded(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        if (byUser.containsKey(userId)) {
            return true;
        }
        List<AgentMemory> memories;
        try {
            memories = memoryMapper.findIndexableByUser(userId, Math.max(1, props.getMaxEntriesPerUser()));
        } catch (Exception e) {
            log.warn("记忆索引装载失败，读路径退回 recency 注入: userId={} err={}", userId, e.toString());
            return false;
        }
        HybridIndex<MemoryEntry> index = new HybridIndex<>();
        index.replace(toDocs(memories));
        // 并发装载时让先到者胜出，后到的那份丢弃（内容一致，代价只是一次多余的向量化）
        HybridIndex<MemoryEntry> existing = byUser.putIfAbsent(userId, index);
        HybridIndex<MemoryEntry> effective = existing != null ? existing : index;
        log.info("记忆索引装载: userId={} 条目={} 向量化={} 模型={}",
                userId, effective.size(), effective.stats().vectorized(), embeddingModelId());
        return true;
    }

    private List<HybridIndex.Doc<MemoryEntry>> toDocs(List<AgentMemory> memories) {
        List<HybridIndex.Doc<MemoryEntry>> docs = new ArrayList<>(memories.size());
        for (int i = 0; i < memories.size(); i += EMBED_BATCH_SIZE) {
            List<AgentMemory> batch = memories.subList(i, Math.min(i + EMBED_BATCH_SIZE, memories.size()));
            List<String> texts = new ArrayList<>(batch.size());
            for (AgentMemory memory : batch) {
                texts.add(memory.getContent());
            }
            List<float[]> vectors = embed(texts);
            for (int j = 0; j < batch.size(); j++) {
                float[] vector = vectors != null && j < vectors.size() ? vectors.get(j) : null;
                docs.add(toDoc(MemoryEntry.from(batch.get(j)), vector));
            }
        }
        return docs;
    }

    // ==================== 读 ====================

    /** 向量召回：只比较同一向量模型、同一维度的条目 */
    public List<HybridIndex.Scored<MemoryEntry>> searchVector(String userId, float[] queryVector, int limit) {
        HybridIndex<MemoryEntry> index = byUser.get(userId);
        return index == null ? List.of() : index.searchVector(queryVector, embeddingModelId(), limit);
    }

    /** 关键词召回：BM25（中文二元组让「简历」「周报」这类短词也能命中） */
    public List<HybridIndex.Scored<MemoryEntry>> searchKeyword(String userId, String query, int limit) {
        HybridIndex<MemoryEntry> index = byUser.get(userId);
        return index == null ? List.of() : index.searchKeyword(query, limit);
    }

    // ==================== 写 ====================

    /** 新增 / 覆盖单条记忆；索引未装载时不做任何事（库里已最新） */
    public void upsert(AgentMemory memory) {
        if (memory == null || memory.getId() == null || memory.getUserId() == null) {
            return;
        }
        HybridIndex<MemoryEntry> index = byUser.get(memory.getUserId());
        if (index == null) {
            return;
        }
        MemoryEntry entry = MemoryEntry.from(memory);
        List<float[]> vectors = embed(List.of(entry.content()));
        float[] vector = vectors != null && !vectors.isEmpty() ? vectors.get(0) : null;
        HybridIndex.Doc<MemoryEntry> doc = toDoc(entry, vector);
        index.update(docs -> {
            List<HybridIndex.Doc<MemoryEntry>> merged = new ArrayList<>(docs.size() + 1);
            for (HybridIndex.Doc<MemoryEntry> existing : docs) {
                if (!existing.key().equals(doc.key())) {
                    merged.add(existing);
                }
            }
            merged.add(doc);
            return merged;
        });
    }

    /** 删除（软删除 / 被取代）同一条记忆 */
    public void remove(String userId, Long memoryId) {
        if (userId == null || memoryId == null) {
            return;
        }
        HybridIndex<MemoryEntry> index = byUser.get(userId);
        if (index == null) {
            return;
        }
        String key = String.valueOf(memoryId);
        index.update(docs -> docs.stream()
                .filter(doc -> !doc.key().equals(key))
                .toList());
    }

    // ==================== 工具 ====================

    /** 当前向量模型标识：向量空间隔离用（换模型后旧向量不会被拿来比较） */
    public String embeddingModelId() {
        if (embeddingModel instanceof HashingEmbeddingModel) {
            return HashingEmbeddingModel.MODEL_NAME;
        }
        String name = embeddingModel.modelName();
        return (name == null || name.isBlank() ? "unknown" : name) + "@" + embeddingModel.dimension();
    }

    /** 向量化单条文本（查询侧使用）；失败返回 null，由调用方降级 */
    public float[] embedOne(String text) {
        List<float[]> vectors = embed(List.of(text));
        return vectors == null || vectors.isEmpty() ? null : vectors.get(0);
    }

    private HybridIndex.Doc<MemoryEntry> toDoc(MemoryEntry entry, float[] vector) {
        return new HybridIndex.Doc<>(String.valueOf(entry.id()), entry.content(), vector, embeddingModelId(), entry);
    }

    /** 批量向量化；任何异常都不抛出，返回空列表表示「本次只有关键词通道可用」。 */
    private List<float[]> embed(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        try {
            List<TextSegment> segments = new ArrayList<>(texts.size());
            for (String text : texts) {
                segments.add(TextSegment.from(text));
            }
            Response<List<Embedding>> response = embeddingModel.embedAll(segments);
            if (response == null || response.content() == null) {
                return List.of();
            }
            List<float[]> vectors = new ArrayList<>(response.content().size());
            for (Embedding embedding : response.content()) {
                float[] vector = embedding.vector();
                EmbeddingCodec.normalize(vector);
                vectors.add(vector);
            }
            return vectors;
        } catch (Exception e) {
            log.warn("记忆向量化失败，本次只走关键词通道: {}", e.toString());
            return List.of();
        }
    }
}