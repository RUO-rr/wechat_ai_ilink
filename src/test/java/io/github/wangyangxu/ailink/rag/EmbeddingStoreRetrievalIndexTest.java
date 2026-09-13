package io.github.wangyangxu.ailink.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.github.wangyangxu.ailink.model.KnowledgeChunk;
import io.github.wangyangxu.ailink.model.KnowledgeDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 向量通道「落到 EmbeddingStore」这一段的单元测试。
 * <p>
 * 用框架自带的 {@link InMemoryEmbeddingStore} 当替身：本类要验证的是「元信息怎么进、怎么出、
 * 过滤与删除是否按文档生效、远端挂掉时怎么降级」，这些都与 Qdrant 无关；
 * 真正需要真库的只有「payload / filter 能否穿过 gRPC」—— 那是 {@code QdrantRetrievalIndexTest} 的职责。
 */
class EmbeddingStoreRetrievalIndexTest {

    private static final String MODEL_ID = HashingEmbeddingModel.MODEL_NAME;

    private final HashingEmbeddingModel embedder = new HashingEmbeddingModel(256);
    private final InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
    private final VectorStoreProperties props = new VectorStoreProperties();
    private final KnowledgeVectorIndex heapIndex = new KnowledgeVectorIndex(false);
    private final Map<Long, KnowledgeDocument> documents = new HashMap<>();

    private EmbeddingStoreRetrievalIndex index;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(props, "provider", "qdrant");
        ReflectionTestUtils.setField(props, "dimension", 256);
        ReflectionTestUtils.setField(props, "rebuildOnBoot", true);
        ReflectionTestUtils.setField(props, "retryBackoffMs", 60_000L);
        index = new EmbeddingStoreRetrievalIndex(heapIndex, store, store::size, props);
    }

    private float[] vector(String text) {
        return embedder.embedAll(List.of(TextSegment.from(text))).content().get(0).vector();
    }

    private KnowledgeChunk chunk(long docId, int chunkIndex, String heading, String text) {
        float[] vector = vector(text);
        return new KnowledgeChunk(docId, chunkIndex, heading, text,
                EmbeddingCodec.encode(vector), vector.length, MODEL_ID);
    }

    private KnowledgeDocument doc(long id, String path, String title) {
        KnowledgeDocument document = new KnowledgeDocument(KnowledgeDocument.SOURCE_RESOURCE, path, title,
                "hash-" + id, MODEL_ID, 256);
        document.setId(id);
        documents.put(id, document);
        return document;
    }

    @Test
    void recallsVectorHitsWithCitationMetadataIntact() {
        KnowledgeDocument document = doc(7L, "resume-builder/references/rules.md", "rules.md");
        index.index(document, List.of(
                chunk(7L, 0, "写作规则", "简历写作要突出量化结果，把性能提升写成具体数字。"),
                chunk(7L, 1, "写作规则", "避免形容词堆砌，用动词开头描述个人贡献。")));

        List<KnowledgeVectorIndex.Scored> hits = index.searchVector(
                vector("简历写作要突出量化结果，把性能提升写成具体数字。"), MODEL_ID, 5);

        assertFalse(hits.isEmpty(), "同一段文本应能召回自身");
        KnowledgeVectorIndex.Entry top = hits.get(0).payload();
        assertEquals(7L, top.documentId());
        assertEquals(0, top.chunkIndex());
        assertEquals("写作规则", top.heading());
        assertEquals("rules.md", top.title());
        assertEquals("resume-builder/references/rules.md", top.sourcePath());
        assertEquals(MODEL_ID, top.embeddingModel());
        assertTrue(top.content().contains("量化结果"), "payload 里必须带回原文，引用才不用回查 MySQL");
        assertEquals("7#0", top.identityKey(), "融合去重键要与内存实现一致");
    }

    @Test
    void keepsVectorsOutOfTheHeapWhenRemote() {
        KnowledgeDocument document = doc(1L, "resume-builder/x.md", "x.md");
        index.index(document, List.of(chunk(1L, 0, null, "支付网关重构把响应时间从 800ms 降到 120ms。")));

        assertEquals(1, store.size(), "向量应写进向量库");
        assertEquals(0, heapIndex.stats().vectorizedChunks(), "远端模式下堆内不应再保留向量");
        assertFalse(heapIndex.searchKeyword("支付网关", 5).isEmpty(), "关键词通道仍在堆内，且必须照常工作");
        assertEquals(1, index.vectorCount(), "点数统计来自写入后的累计值");
    }

    @Test
    void onlyComparesSameEmbeddingModel() {
        KnowledgeChunk otherModel = new KnowledgeChunk(2L, 0, null, "向量模型换过但尚未重灌的片段",
                EmbeddingCodec.encode(vector("向量模型换过但尚未重灌的片段")), 256, "other-model@1024");
        index.index(doc(2L, "resume-builder/y.md", "y.md"), List.of(otherModel));

        assertTrue(index.searchVector(vector("向量模型换过但尚未重灌的片段"), MODEL_ID, 5).isEmpty(),
                "模型不一致的片段不能混进同一路排序");
        assertFalse(index.searchVector(vector("向量模型换过但尚未重灌的片段"), "other-model@1024", 5).isEmpty());
    }

    @Test
    void reindexingDocumentReplacesInsteadOfLeavingGhostPoints() {
        KnowledgeDocument document = doc(3L, "resume-builder/z.md", "z.md");
        index.index(document, List.of(
                chunk(3L, 0, null, "第一段：面试要准备项目复盘。"),
                chunk(3L, 1, null, "第二段：面试要准备反问环节。")));
        assertEquals(2, store.size());

        // 内容改写后片段数变少：旧的第 2 段必须从向量库里消失（覆盖走 replace）
        index.replace(document, List.of(chunk(3L, 0, null, "第一段：面试要准备项目复盘和反问环节。")));

        assertEquals(1, store.size(), "重新入库应覆盖而不是叠加");
        assertTrue(index.searchVector(vector("面试要准备反问环节"), MODEL_ID, 5).stream()
                        .noneMatch(hit -> hit.payload().chunkIndex() == 1),
                "被删掉的片段（chunk 1）不能再被召回");
    }

    @Test
    void removesDocumentFromBothChannels() {
        KnowledgeDocument keep = doc(11L, "resume-builder/keep.md", "keep.md");
        KnowledgeDocument drop = doc(12L, "resume-builder/drop.md", "drop.md");
        index.index(keep, List.of(chunk(11L, 0, null, "岗位调研要收集招聘 JD 与行业动态。")));
        index.index(drop, List.of(chunk(12L, 0, null, "薪酬谈判前先确认市场分位数。")));

        index.removeDocument(12L);

        assertEquals(1, store.size());
        assertTrue(index.searchVector(vector("薪酬谈判前先确认市场分位数。"), MODEL_ID, 5).stream()
                        .noneMatch(hit -> hit.payload().documentId() == 12L),
                "被删除文档的片段不能再被召回");
        assertTrue(index.searchKeyword("薪酬谈判", 5).isEmpty(), "关键词通道也要同步删除");
        assertFalse(index.searchKeyword("岗位调研", 5).isEmpty());
    }

    @Test
    void rebuildBackfillsOnlyWhenRemoteCollectionIsEmpty() {
        KnowledgeDocument document = doc(21L, "resume-builder/rebuild.md", "rebuild.md");
        List<KnowledgeChunk> chunks = List.of(
                chunk(21L, 0, null, "内置资产：简历评价标准分四档。"),
                chunk(21L, 1, null, "内置资产：模板分为学术与商务两类。"));

        index.rebuild(chunks, documents);
        assertEquals(2, store.size(), "集合为空时应从库内片段回灌");
        assertFalse(index.searchVector(vector("简历评价标准分四档"), MODEL_ID, 5).isEmpty());

        // 第二次重建：集合里已有数据，不应重复灌入
        index.rebuild(chunks, documents);
        assertEquals(2, store.size(), "重复重建应跳过回灌，避免重复点");
    }

    @Test
    void degradesToKeywordOnlyWhenRemoteFailsAndRecoversAfterBackoff() {
        ReflectionTestUtils.setField(props, "retryBackoffMs", 200L);
        ThrowingStore broken = new ThrowingStore();
        EmbeddingStoreRetrievalIndex failing = new EmbeddingStoreRetrievalIndex(
                new KnowledgeVectorIndex(false), broken, VectorStoreAdmin.NONE, props);

        failing.index(doc(31L, "resume-builder/f.md", "f.md"),
                List.of(chunk(31L, 0, null, "向量库写不进去也不能影响入库。")));

        assertFalse(failing.available(), "远端失败后应进入退避窗口");
        assertTrue(failing.searchVector(vector("向量库写不进去也不能影响入库。"), MODEL_ID, 5).isEmpty(),
                "退避窗口内向量一路返回空，由上层只跑关键词，而不是抛异常");
        assertEquals(0, broken.searches(), "退避窗口内不该再去撞一台挂掉的服务");
        assertEquals(0, failing.vectorCount());

        sleep(260);
        assertTrue(failing.available(), "退避窗口过后应放行一次探测");
        assertTrue(failing.searchVector(vector("向量库写不进去也不能影响入库。"), MODEL_ID, 5).isEmpty());
        assertEquals(1, broken.searches(), "窗口过后只探测一次；再失败就继续退避");
        assertFalse(failing.available());
    }

    @Test
    void pointIdIsStableDistinctAndUuidShaped() {
        String first = EmbeddingStoreRetrievalIndex.pointId(9L, 0);
        assertEquals(first, EmbeddingStoreRetrievalIndex.pointId(9L, 0), "同片段必须得到同一个 id（幂等写入）");
        assertNotEquals(first, EmbeddingStoreRetrievalIndex.pointId(9L, 1));
        assertNotEquals(first, EmbeddingStoreRetrievalIndex.pointId(10L, 0));
        assertEquals(first, UUID.fromString(first).toString(), "Qdrant 点 id 只接受 UUID / uint64");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 永远失败的向量库：用来验证「远端挂掉也只降到关键词」这条降级路径。 */
    private static final class ThrowingStore implements EmbeddingStore<TextSegment> {

        private final java.util.concurrent.atomic.AtomicInteger searchCalls = new java.util.concurrent.atomic.AtomicInteger();

        int searches() {
            return searchCalls.get();
        }

        @Override
        public String add(Embedding embedding) {
            throw new IllegalStateException("向量库不可用");
        }

        @Override
        public void add(String id, Embedding embedding) {
            throw new IllegalStateException("向量库不可用");
        }

        @Override
        public String add(Embedding embedding, TextSegment segment) {
            throw new IllegalStateException("向量库不可用");
        }

        @Override
        public List<String> addAll(List<Embedding> embeddings) {
            throw new IllegalStateException("向量库不可用");
        }

        @Override
        public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> segments) {
            throw new IllegalStateException("向量库不可用");
        }

        @Override
        public void removeAll(dev.langchain4j.store.embedding.filter.Filter filter) {
            throw new IllegalStateException("向量库不可用");
        }

        @Override
        public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
            searchCalls.incrementAndGet();
            throw new IllegalStateException("向量库不可用");
        }
    }
}