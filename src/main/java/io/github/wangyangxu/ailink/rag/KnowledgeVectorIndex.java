package io.github.wangyangxu.ailink.rag;

import io.github.wangyangxu.ailink.model.KnowledgeChunk;
import io.github.wangyangxu.ailink.model.KnowledgeDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 知识库混合索引 —— 向量通道（余弦）+ 关键词通道（BM25），实现委托给通用内核 {@link HybridIndex}。
 * <p>
 * <b>为什么放内存</b>：当前语料是「内置资产 + 用户上传文档」，量级在千片段以内，
 * 全量载入内存后单次检索是毫秒级，比每次查库再算相似度便宜得多，也省掉一个向量数据库组件。
 * 索引以不可变快照发布（写入时整体重建后替换引用），读路径无锁、无半成品状态；
 * 语料规模长到内存吃不下时，替换本类实现（HNSW / pgvector / Milvus）即可，检索侧接口不变。
 * <p>
 * 本类只负责「知识库条目长什么样、怎么增删」；打分与融合在内核里，与长期记忆共用。
 */
@Component
public class KnowledgeVectorIndex {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeVectorIndex.class);

    /** 索引条目：片段正文 + 文档元信息（引用展示用）+ 向量 */
    public record Entry(long chunkId,
                        long documentId,
                        int chunkIndex,
                        String heading,
                        String content,
                        String sourcePath,
                        String title,
                        float[] vector,
                        String embeddingModel) {

        /** 片段唯一标识：入库时未回填自增主键，用「文档 + 序号」稳定标识同一条片段。 */
        public String identityKey() {
            return documentId + "#" + chunkIndex;
        }
    }

    /** 一路召回结果：实现 {@link HybridFusion.Hit}，融合逻辑不依赖本类的具体形态 */
    public record Scored(Entry entry, double score) implements HybridFusion.Hit<Entry> {
        @Override
        public String key() { return entry.identityKey(); }

        @Override
        public Entry payload() { return entry; }
    }

    public record Stats(int documents, int chunks, int vectorizedChunks, int dimensions) {}

    private final HybridIndex<Entry> core = new HybridIndex<>();

    // ==================== 写入（copy-on-write，读路径无锁） ====================

    /** 启动时全量重建：从库里的片段恢复索引，无需重新调用向量模型。 */
    public void rebuild(List<KnowledgeChunk> chunks, Map<Long, KnowledgeDocument> documentsById) {
        List<HybridIndex.Doc<Entry>> docs = new ArrayList<>(chunks.size());
        for (KnowledgeChunk chunk : chunks) {
            docs.add(toDoc(chunk, documentsById.get(chunk.getDocumentId())));
        }
        core.replace(docs);
        log.info("知识库索引重建完成: 片段={}, 文档={}", docs.size(), documentsById.size());
    }

    /** 单文档增量更新：先摘掉该文档旧片段，再挂上新片段。 */
    public void upsert(KnowledgeDocument document, List<KnowledgeChunk> chunks) {
        List<HybridIndex.Doc<Entry>> additions = new ArrayList<>(chunks.size());
        for (KnowledgeChunk chunk : chunks) {
            additions.add(toDoc(chunk, document));
        }
        core.update(docs -> {
            List<HybridIndex.Doc<Entry>> merged = new ArrayList<>(docs.size() + additions.size());
            for (HybridIndex.Doc<Entry> doc : docs) {
                if (doc.payload().documentId() != document.getId()) {
                    merged.add(doc);
                }
            }
            merged.addAll(additions);
            return merged;
        });
    }

    public void removeDocument(long documentId) {
        core.update(docs -> docs.stream()
                .filter(doc -> doc.payload().documentId() != documentId)
                .toList());
    }

    private HybridIndex.Doc<Entry> toDoc(KnowledgeChunk chunk, KnowledgeDocument doc) {
        Entry entry = new Entry(
                chunk.getId() == null ? -1L : chunk.getId(),
                chunk.getDocumentId(),
                chunk.getChunkIndex(),
                chunk.getHeading(),
                chunk.getContent(),
                doc == null ? "unknown" : doc.getSourcePath(),
                doc == null ? "unknown" : doc.getTitle(),
                EmbeddingCodec.decode(chunk.getEmbedding()),
                chunk.getEmbeddingModel());
        return new HybridIndex.Doc<>(entry.identityKey(), entry.content(),
                entry.vector(), entry.embeddingModel(), entry);
    }

    // ==================== 读取 ====================

    /**
     * 向量召回：只比较同一向量模型、同一维度的片段。
     * 换过模型（如本地降级向量 → DashScope）但尚未重新索引的片段会被自然排除，不会污染排序。
     */
    public List<Scored> searchVector(float[] queryVector, String embeddingModel, int limit) {
        return core.searchVector(queryVector, embeddingModel, limit).stream()
                .map(hit -> new Scored(hit.doc().payload(), hit.score()))
                .toList();
    }

    /** 关键词召回：BM25。中文字符二元组让「种族值修改」这类词也能命中。 */
    public List<Scored> searchKeyword(String query, int limit) {
        return core.searchKeyword(query, limit).stream()
                .map(hit -> new Scored(hit.doc().payload(), hit.score()))
                .toList();
    }

    public Stats stats() {
        HybridIndex.Stats stats = core.stats();
        long documents = core.docs().stream().mapToLong(doc -> doc.payload().documentId()).distinct().count();
        return new Stats((int) documents, stats.size(), stats.vectorized(), stats.dimensions());
    }

    /** 某文档在索引中的片段（按序号排序），用于长文档的覆盖率采样。 */
    public List<Entry> entriesOfDocument(long documentId) {
        List<Entry> entries = new ArrayList<>();
        for (HybridIndex.Doc<Entry> doc : core.docs()) {
            if (doc.payload().documentId() == documentId) {
                entries.add(doc.payload());
            }
        }
        entries.sort(Comparator.comparingInt(Entry::chunkIndex));
        return entries;
    }

    public boolean isEmpty() {
        return core.isEmpty();
    }

    public int size() {
        return core.size();
    }
}