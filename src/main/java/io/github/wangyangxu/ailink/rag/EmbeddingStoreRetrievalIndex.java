package io.github.wangyangxu.ailink.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import io.github.wangyangxu.ailink.model.KnowledgeChunk;
import io.github.wangyangxu.ailink.model.KnowledgeDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 向量通道落在 LangChain4j {@link EmbeddingStore} 上的实现（生产配置接 Qdrant，HNSW）。
 * <p>
 * <b>职责边界</b>：关键词通道仍在进程内（BM25 依赖全文，换存储没有收益），本类只把向量搬出去，
 * 于是 JVM 堆里不再持有 10 万 × 1024 维的浮点数组。对外行为与
 * {@link InMemoryRetrievalIndex} 一致：同样的命中结构（{@link KnowledgeVectorIndex.Scored}）、
 * 同样的「只比较同一向量模型」约束，上层融合逻辑不感知差别。
 * <p>
 * <b>三个实现细节值得说明</b>：
 * <ol>
 *   <li><b>点 id 由内容派生</b>：{@code documentId#chunkIndex} 取 UUID v3。同一片段重复写入是
 *       覆盖而不是新增，所以「重启回灌」不会产生重复点，也不需要先清空集合。</li>
 *   <li><b>元信息扁平存在 payload 里</b>（document_id / chunk_index / heading / source_path / title /
 *       embedding_model）：检索命中后不必回查 MySQL 就能拼出引用，这一条直接决定了引用来源、
 *       文档配额与上下文拼装能不能照旧工作。</li>
 *   <li><b>失败即降级、且不反复撞墙</b>：任何远端异常都只记录一次告警并进入退避窗口，
 *       窗口内的查询直接返回空向量命中 —— 上层本来就有「向量为空则只跑关键词」的降级分支，
 *       所以向量库挂掉只会让检索变粗糙，不会让问答失败。</li>
 * </ol>
 */
public class EmbeddingStoreRetrievalIndex implements RetrievalIndex {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingStoreRetrievalIndex.class);

    /** 元信息键：扁平存进向量库 payload，命中后据此还原引用信息。 */
    static final String KEY_CHUNK_ID = "chunk_id";
    static final String KEY_DOCUMENT_ID = "document_id";
    static final String KEY_CHUNK_INDEX = "chunk_index";
    static final String KEY_HEADING = "heading";
    static final String KEY_SOURCE_PATH = "source_path";
    static final String KEY_TITLE = "title";
    static final String KEY_SOURCE_TYPE = "source_type";
    static final String KEY_MODEL = "embedding_model";

    /** 回灌 / 批量写入的分批大小：单次 gRPC 请求别塞太大。 */
    private static final int WRITE_BATCH = 64;

    private final KnowledgeVectorIndex keywordIndex;
    private final EmbeddingStore<TextSegment> store;
    private final VectorStoreAdmin admin;
    private final VectorStoreProperties props;

    /** 远端可用性：失败进入退避窗口，窗口内不再尝试；窗口过后放一次探测。 */
    private volatile boolean healthy = true;
    private volatile long nextProbeAt = 0L;
    /** 最近一次成功读到的点数，点位接口不可用时用它兜底展示。 */
    private volatile long lastKnownCount = 0L;

    public EmbeddingStoreRetrievalIndex(KnowledgeVectorIndex keywordIndex,
                                       EmbeddingStore<TextSegment> store,
                                       VectorStoreAdmin admin,
                                       VectorStoreProperties props) {
        this.keywordIndex = keywordIndex;
        this.store = store;
        this.admin = admin == null ? VectorStoreAdmin.NONE : admin;
        this.props = props;
    }

    @Override
    public String provider() {
        return props.getProvider();
    }

    @Override
    public boolean available() {
        return healthy || System.currentTimeMillis() >= nextProbeAt;
    }

    // ==================== 写入 ====================

    @Override
    public void index(KnowledgeDocument document, List<KnowledgeChunk> chunks) {
        keywordIndex.upsert(document, chunks);
        writeVectors(document, chunks);
    }

    @Override
    public void replace(KnowledgeDocument document, List<KnowledgeChunk> chunks) {
        keywordIndex.upsert(document, chunks);
        if (document == null || document.getId() == null) {
            return;
        }
        // 覆盖前先按文档清掉旧点：重新入库时片段数可能变少，
        // 只写新片段会留下「向量库里有、文本已经删了」的幽灵点。
        // 新增文档走 index()，不做这次删除 —— 一千份文档首次入库时那是二十秒的白工。
        try {
            store.removeAll(MetadataFilterBuilder.metadataKey(KEY_DOCUMENT_ID).isEqualTo(document.getId()));
        } catch (Exception e) {
            markUnhealthy("清理旧向量失败", e);
            return;
        }
        writeVectors(document, chunks);
    }

    /** 真正的向量写入（新增与覆盖共用）。 */
    private void writeVectors(KnowledgeDocument document, List<KnowledgeChunk> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        List<String> ids = new ArrayList<>(chunks.size());
        List<Embedding> embeddings = new ArrayList<>(chunks.size());
        List<TextSegment> segments = new ArrayList<>(chunks.size());
        for (KnowledgeChunk chunk : chunks) {
            float[] vector = EmbeddingCodec.decode(chunk.getEmbedding());
            if (vector == null || vector.length == 0) {
                continue;
            }
            ids.add(pointId(chunk.getDocumentId(), chunk.getChunkIndex()));
            embeddings.add(Embedding.from(vector));
            segments.add(toSegment(document, chunk));
        }
        if (ids.isEmpty()) {
            return;
        }
        try {
            store.addAll(ids, embeddings, segments);
            markHealthy();
            lastKnownCount += ids.size();
        } catch (Exception e) {
            markUnhealthy("写入失败", e);
        }
    }

    @Override
    public void removeDocument(long documentId) {
        keywordIndex.removeDocument(documentId);
        try {
            store.removeAll(MetadataFilterBuilder.metadataKey(KEY_DOCUMENT_ID).isEqualTo(documentId));
            markHealthy();
            long count = admin.count();
            if (count >= 0) {
                lastKnownCount = count;
            }
        } catch (Exception e) {
            markUnhealthy("删除失败", e);
        }
    }

    @Override
    public void rebuild(List<KnowledgeChunk> chunks, Map<Long, KnowledgeDocument> documentsById) {
        keywordIndex.rebuild(chunks, documentsById);
        long existing = refreshCount();
        if (existing > 0) {
            log.info("向量库已有 {} 个点，跳过启动回灌（需整库重灌请清空集合 {}）", existing, props.getCollection());
            return;
        }
        if (!props.isRebuildOnBoot()) {
            return;
        }
        if (!available()) {
            log.warn("向量库当前不可用，本轮不回灌；下次启动或恢复后重试");
            return;
        }
        try {
            int pushed = backfill(chunks, documentsById);
            markHealthy();
            lastKnownCount = Math.max(lastKnownCount, pushed);
            log.info("向量库为空，已从库内片段回灌 {} 个向量（点 id 由 文档#片段 派生，重复回灌是覆盖）", pushed);
        } catch (Exception e) {
            markUnhealthy("回灌失败", e);
        }
    }

    /** 把库内已存的向量分批灌进向量库；调用方负责异常处理。 */
    private int backfill(List<KnowledgeChunk> chunks, Map<Long, KnowledgeDocument> documentsById) {
        List<String> ids = new ArrayList<>(WRITE_BATCH);
        List<Embedding> embeddings = new ArrayList<>(WRITE_BATCH);
        List<TextSegment> segments = new ArrayList<>(WRITE_BATCH);
        int pushed = 0;
        for (KnowledgeChunk chunk : chunks) {
            float[] vector = EmbeddingCodec.decode(chunk.getEmbedding());
            if (vector == null || vector.length == 0) {
                continue;
            }
            ids.add(pointId(chunk.getDocumentId(), chunk.getChunkIndex()));
            embeddings.add(Embedding.from(vector));
            segments.add(toSegment(documentsById.get(chunk.getDocumentId()), chunk));
            if (ids.size() >= WRITE_BATCH) {
                store.addAll(ids, embeddings, segments);
                pushed += ids.size();
                ids.clear();
                embeddings.clear();
                segments.clear();
            }
        }
        if (!ids.isEmpty()) {
            store.addAll(ids, embeddings, segments);
            pushed += ids.size();
        }
        return pushed;
    }

    // ==================== 读取 ====================

    @Override
    public List<KnowledgeVectorIndex.Scored> searchVector(float[] queryVector, String embeddingModel, int limit) {
        if (queryVector == null || queryVector.length == 0 || limit <= 0 || !available()) {
            return List.of();
        }
        EmbeddingSearchRequest.EmbeddingSearchRequestBuilder request = EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(queryVector))
                .maxResults(limit)
                .minScore(0d);
        if (embeddingModel != null && !embeddingModel.isBlank()) {
            // 与内存实现同一条约束：换过向量模型但尚未重灌的片段不参与排序
            request.filter(MetadataFilterBuilder.metadataKey(KEY_MODEL).isEqualTo(embeddingModel));
        }
        try {
            List<EmbeddingMatch<TextSegment>> matches = store.search(request.build()).matches();
            markHealthy();
            List<KnowledgeVectorIndex.Scored> hits = new ArrayList<>(matches.size());
            for (EmbeddingMatch<TextSegment> match : matches) {
                if (match.embedded() == null || match.score() == null || match.score() <= 0d) {
                    continue;
                }
                hits.add(new KnowledgeVectorIndex.Scored(toEntry(match.embedded()), match.score()));
            }
            return hits;
        } catch (Exception e) {
            markUnhealthy("检索失败", e);
            return List.of();
        }
    }

    @Override
    public List<KnowledgeVectorIndex.Scored> searchKeyword(String query, int limit) {
        return keywordIndex.searchKeyword(query, limit);
    }

    @Override
    public List<KnowledgeVectorIndex.Entry> entriesOfDocument(long documentId) {
        return keywordIndex.entriesOfDocument(documentId);
    }

    // ==================== 统计 ====================

    @Override
    public int chunkCount() {
        return keywordIndex.size();
    }

    @Override
    public int vectorCount() {
        long count = refreshCount();
        long effective = count >= 0 ? count : lastKnownCount;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, effective));
    }

    @Override
    public int dimensions() {
        return props.getDimension();
    }

    @Override
    public boolean isEmpty() {
        return keywordIndex.isEmpty();
    }

    // ==================== 内部工具 ====================

    /** 问一次点数并缓存；管理通道不可用时返回 -1（调用方用缓存值兜底）。 */
    private long refreshCount() {
        long count = admin.count();
        if (count >= 0) {
            lastKnownCount = count;
        }
        return count;
    }

    private void markHealthy() {
        if (!healthy) {
            log.info("向量库已恢复，向量召回重新启用");
        }
        healthy = true;
        nextProbeAt = 0L;
    }

    private void markUnhealthy(String what, Exception e) {
        boolean firstFailure = healthy;
        healthy = false;
        nextProbeAt = System.currentTimeMillis() + Math.max(0L, props.getRetryBackoffMs());
        if (firstFailure) {
            // 第一次失败带上完整栈：降级是「设计内的行为」，但把 NPE 这类真 bug 藏进一行被吞的
            // toString 会让排查变成猜谜（这个告警本身就是靠集成测试才发现写入一直在失败）。
            log.warn("向量库{}，{}ms 内降级为纯关键词召回", what, props.getRetryBackoffMs(), e);
        } else {
            log.debug("向量库仍不可用（{}）: {}", what, e.toString());
        }
    }

    /** 元信息 → 向量库 payload（LangChain4j 会把 Metadata 的每个键平铺进 payload）。 */
    private static TextSegment toSegment(KnowledgeDocument document, KnowledgeChunk chunk) {
        Metadata metadata = new Metadata()
                .put(KEY_DOCUMENT_ID, chunk.getDocumentId() == null ? -1L : chunk.getDocumentId())
                .put(KEY_CHUNK_INDEX, chunk.getChunkIndex())
                .put(KEY_MODEL, nullSafe(chunk.getEmbeddingModel()));
        if (chunk.getId() != null) {
            metadata.put(KEY_CHUNK_ID, chunk.getId());
        }
        if (document != null) {
            metadata.put(KEY_SOURCE_PATH, nullSafe(document.getSourcePath()))
                    .put(KEY_TITLE, nullSafe(document.getTitle()))
                    .put(KEY_SOURCE_TYPE, nullSafe(document.getSourceType()));
        }
        if (chunk.getHeading() != null && !chunk.getHeading().isBlank()) {
            metadata.put(KEY_HEADING, chunk.getHeading());
        }
        return TextSegment.from(chunk.getContent() == null ? "" : chunk.getContent(), metadata);
    }

    /** payload → 命中条目（向量本身不回填：融合与引用都不再需要它）。 */
    private static KnowledgeVectorIndex.Entry toEntry(TextSegment segment) {
        Metadata metadata = segment.metadata();
        return new KnowledgeVectorIndex.Entry(
                asLong(metadata, KEY_CHUNK_ID, -1L),
                asLong(metadata, KEY_DOCUMENT_ID, -1L),
                (int) asLong(metadata, KEY_CHUNK_INDEX, -1L),
                asString(metadata, KEY_HEADING),
                segment.text(),
                asString(metadata, KEY_SOURCE_PATH),
                asString(metadata, KEY_TITLE),
                null,
                asString(metadata, KEY_MODEL));
    }

    /** 稳定点 id：同一 文档#片段 每次得到同一个 UUID，写入天然幂等。 */
    static String pointId(Long documentId, int chunkIndex) {
        String identity = (documentId == null ? -1L : documentId) + "#" + chunkIndex;
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * 数值读取要容忍类型漂移：内存实现存进去的是 Long，Qdrant 的 payload 回来后
     * 可能是 Long / Double / String，这里统一收口，免得每个字段各写一遍转换。
     */
    private static long asLong(Metadata metadata, String key, long fallback) {
        Object value = metadata.toMap().get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String asString(Metadata metadata, String key) {
        Object value = metadata.toMap().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String nullSafe(String text) {
        return text == null ? "" : text;
    }
}