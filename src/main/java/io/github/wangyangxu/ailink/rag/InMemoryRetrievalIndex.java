package io.github.wangyangxu.ailink.rag;

import io.github.wangyangxu.ailink.model.KnowledgeChunk;
import io.github.wangyangxu.ailink.model.KnowledgeDocument;

import java.util.List;
import java.util.Map;

/**
 * 默认实现：向量与关键词两通道都跑在进程内（{@link KnowledgeVectorIndex} / HybridIndex 内核）。
 * <p>
 * 千级片段时这是最优解 —— 一次余弦扫描是微秒级，比任何网络往返都便宜，也省掉一个外部组件。
 * 本类只做端口到内核的转接，真正的打分、快照、BM25 都在内核里，与长期记忆共用同一份实现。
 */
public class InMemoryRetrievalIndex implements RetrievalIndex {

    private final KnowledgeVectorIndex index;

    public InMemoryRetrievalIndex(KnowledgeVectorIndex index) {
        this.index = index;
    }

    @Override
    public String provider() {
        return "in-memory";
    }

    @Override
    public boolean available() {
        return true;
    }

    // ==================== 写入 ====================

    @Override
    public void index(KnowledgeDocument document, List<KnowledgeChunk> chunks) {
        index.upsert(document, chunks);
    }

    @Override
    public void replace(KnowledgeDocument document, List<KnowledgeChunk> chunks) {
        // 内存实现里 upsert 本身就是「按文档覆盖」，新增与覆盖走同一条路
        index.upsert(document, chunks);
    }

    @Override
    public void removeDocument(long documentId) {
        index.removeDocument(documentId);
    }

    @Override
    public void rebuild(List<KnowledgeChunk> chunks, Map<Long, KnowledgeDocument> documentsById) {
        index.rebuild(chunks, documentsById);
    }

    // ==================== 读取 ====================

    @Override
    public List<KnowledgeVectorIndex.Scored> searchVector(float[] queryVector, String embeddingModel, int limit) {
        return index.searchVector(queryVector, embeddingModel, limit);
    }

    @Override
    public List<KnowledgeVectorIndex.Scored> searchKeyword(String query, int limit) {
        return index.searchKeyword(query, limit);
    }

    @Override
    public List<KnowledgeVectorIndex.Entry> entriesOfDocument(long documentId) {
        return index.entriesOfDocument(documentId);
    }

    // ==================== 统计 ====================

    @Override
    public int chunkCount() {
        return index.size();
    }

    @Override
    public int vectorCount() {
        return index.stats().vectorizedChunks();
    }

    @Override
    public int dimensions() {
        return index.stats().dimensions();
    }

    @Override
    public boolean isEmpty() {
        return index.isEmpty();
    }
}