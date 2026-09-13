package io.github.wangyangxu.ailink.rag;

import io.github.wangyangxu.ailink.model.KnowledgeChunk;
import io.github.wangyangxu.ailink.model.KnowledgeDocument;

import java.util.List;
import java.util.Map;

/**
 * 知识库检索索引端口 —— 写入与读取都从这里进出，向量通道的落点由 provider 决定。
 * <p>
 * <b>为什么要有这个端口</b>：项目里会随规模变化的东西只有一件 —— <i>向量存哪、怎么比</i>。
 * 关键词通道（BM25）依赖全文分词与倒排表，无论多少片段都得把文本载入进程内存，
 * 换存储没有收益；向量通道则相反：千级片段时堆内暴力扫描最快，十万级片段时
 * 堆内光向量就占几百 MB，必须换成 HNSW 之类的近似索引。所以端口只把「会变的那条通道」
 * 隔离出来，同时把「写入 / 读取 / 统计」三件事一起收口，避免调用方直接依赖具体实现。
 * <p>
 * 两个实现：
 * <ul>
 *   <li>{@link InMemoryRetrievalIndex} —— 默认，两通道都在堆内（复用 HybridIndex 内核）；</li>
 *   <li>{@link EmbeddingStoreRetrievalIndex} —— 向量落 LangChain4j {@code EmbeddingStore}
 *       （当前接 Qdrant，HNSW），关键词仍在堆内。</li>
 * </ul>
 * 两种实现对外行为一致（同样的命中结构、同样的融合输入），因此上层的混合召回与融合逻辑
 * 不需要知道自己跑在哪种 provider 上。
 */
public interface RetrievalIndex {

    /** 当前向量通道的实现标识：in-memory | qdrant，用于日志与自检接口。 */
    String provider();

    /** 向量通道当前是否可用（内存实现恒为 true；远端库断连时为 false，调用方据此只跑关键词）。 */
    boolean available();

    // ==================== 写入 ====================

    /** 写入一份<b>新</b>文档的全部片段（关键词通道内部覆盖同文档旧片段；向量库直接新增）。 */
    void index(KnowledgeDocument document, List<KnowledgeChunk> chunks);

    /**
     * 覆盖一份<b>已存在</b>文档：关键词通道摘下旧片段再挂新的；向量库先按 document_id
     * 清掉旧点再写入 —— 重新入库时片段数可能变少，只写新片段会留下「向量库有、文本已删」的幽灵点。
     * 与 {@link #index} 分开是为了让调用方（它本来就知道文档是新增还是覆盖）把这件事说清楚，
     * 而不是让实现去猜，或者对每个新文档都白做一次删除。
     */
    void replace(KnowledgeDocument document, List<KnowledgeChunk> chunks);

    /** 删除一份文档在索引中的全部片段（两个通道一起删）。 */
    void removeDocument(long documentId);

    /** 全量重建：启动时从库内片段恢复索引，避免重新调用向量模型。 */
    void rebuild(List<KnowledgeChunk> chunks, Map<Long, KnowledgeDocument> documentsById);

    // ==================== 读取 ====================

    /** 向量召回（余弦）：只比较同一向量模型的片段。 */
    List<KnowledgeVectorIndex.Scored> searchVector(float[] queryVector, String embeddingModel, int limit);

    /** 关键词召回（BM25）。 */
    List<KnowledgeVectorIndex.Scored> searchKeyword(String query, int limit);

    /** 某文档在索引中的片段（按序号排序），用于长文档覆盖率采样。 */
    List<KnowledgeVectorIndex.Entry> entriesOfDocument(long documentId);

    // ==================== 统计 ====================

    /** 索引内的片段总数（两种实现都是堆内文本条数）。 */
    int chunkCount();

    /** 已向量化的片段数：内存实现数堆内向量，远端实现问向量库的点数。 */
    int vectorCount();

    /** 向量维度（无向量时返回 0）。 */
    int dimensions();

    boolean isEmpty();
}