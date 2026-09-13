package io.github.wangyangxu.ailink.rag;

import dev.langchain4j.data.segment.TextSegment;
import io.github.wangyangxu.ailink.model.KnowledgeChunk;
import io.github.wangyangxu.ailink.model.KnowledgeDocument;
import io.qdrant.client.QdrantClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Qdrant 集成测试：只有一件事是内存替身测不出来的 —— <b>payload 与 filter 能不能真的穿过 gRPC 往返</b>。
 * <p>
 * 覆盖三点：元信息写入后能不能原样读回（引用、文档配额都靠它）、按文档删除能否生效、
 * 「集合为空才回灌」这条启动策略在真实点数下是否成立。
 * <p>
 * 需要本机 Qdrant 在 127.0.0.1:6334 上（<code>D:\tools\qdrant\start-qdrant.bat</code>）。
 * 没启动时自动跳过，不让别人 clone 下来因为缺一个外部服务就红一片。
 * 每个用例用独立集合（跑完即删），互不累加点数，也不污染 ai_ilink_knowledge。
 */
class QdrantRetrievalIndexTest {

    private static final String MODEL_ID = HashingEmbeddingModel.MODEL_NAME;

    private QdrantClient client;
    private String collection;

    private final HashingEmbeddingModel embedder = new HashingEmbeddingModel(256);
    private final VectorStoreProperties props = new VectorStoreProperties();
    private final KnowledgeVectorIndex heapIndex = new KnowledgeVectorIndex(false);
    private final Map<Long, KnowledgeDocument> documents = new HashMap<>();

    private EmbeddingStoreRetrievalIndex index;

    @BeforeEach
    void connectToQdrant(TestInfo testInfo) {
        String name = testInfo.getTestMethod().map(Method::getName).orElse("case");
        collection = "ai_ilink_it_" + name.toLowerCase() + "_" + UUID.randomUUID().toString().substring(0, 6);
        ReflectionTestUtils.setField(props, "provider", "qdrant");
        ReflectionTestUtils.setField(props, "host", "127.0.0.1");
        ReflectionTestUtils.setField(props, "port", 6334);
        ReflectionTestUtils.setField(props, "collection", collection);
        ReflectionTestUtils.setField(props, "dimension", 256);
        ReflectionTestUtils.setField(props, "payloadTextKey", "text_segment");
        ReflectionTestUtils.setField(props, "rebuildOnBoot", true);
        ReflectionTestUtils.setField(props, "requestTimeoutMs", 5_000L);
        ReflectionTestUtils.setField(props, "retryBackoffMs", 30_000L);
        try {
            QdrantVectorStoreFactory.Connection connection = QdrantVectorStoreFactory.open(props);
            client = connection.client();
            index = new EmbeddingStoreRetrievalIndex(heapIndex, connection.store(), connection.admin(), props);
        } catch (Exception e) {
            Assumptions.abort("本机未启动 Qdrant（" + e.getMessage() + "），跳过集成测试");
        }
    }

    @AfterEach
    void dropCollection() {
        if (client == null) {
            return;
        }
        try {
            client.deleteCollectionAsync(collection).get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // 清理失败不影响结论：集合名带随机后缀，不会与真实知识库撞车
        } finally {
            client.close();
            client = null;
        }
    }

    private float[] vector(String text) {
        return embedder.embedAll(List.of(TextSegment.from(text))).content().get(0).vector();
    }

    private KnowledgeChunk chunk(long docId, int chunkIndex, String heading, String text, String model) {
        float[] vector = vector(text);
        return new KnowledgeChunk(docId, chunkIndex, heading, text,
                EmbeddingCodec.encode(vector), vector.length, model);
    }

    private KnowledgeDocument doc(long id, String path, String title) {
        KnowledgeDocument document = new KnowledgeDocument(KnowledgeDocument.SOURCE_USER_UPLOAD, path, title,
                "hash-" + id, MODEL_ID, 256);
        document.setId(id);
        documents.put(id, document);
        return document;
    }

    @Test
    void payloadSurvivesGrpcRoundTripWithCitations() {
        KnowledgeDocument document = doc(1L, "data/documents/我的简历.docx", "我的简历.docx");
        index.index(document, List.of(
                chunk(1L, 0, "项目经历", "候选人负责支付网关重构，把响应时间从 800ms 降到 120ms。", MODEL_ID),
                chunk(1L, 1, "技能清单", "熟悉 Java、Spring Boot 与 MySQL 调优。", MODEL_ID)));

        List<KnowledgeVectorIndex.Scored> hits = index.searchVector(
                vector("候选人负责支付网关重构，把响应时间从 800ms 降到 120ms。"), MODEL_ID, 5);

        assertFalse(hits.isEmpty(), "写入 Qdrant 后应能召回");
        KnowledgeVectorIndex.Entry top = hits.get(0).payload();
        assertEquals(1L, top.documentId(), "document_id 必须穿过 gRPC 往返");
        assertEquals(0, top.chunkIndex(), "chunk_index 是融合去重键的一半");
        assertEquals("项目经历", top.heading());
        assertEquals("我的简历.docx", top.title());
        assertEquals("data/documents/我的简历.docx", top.sourcePath());
        assertEquals(MODEL_ID, top.embeddingModel());
        assertTrue(top.content().contains("支付网关"), "原文要能从 payload 读回，引用才不用回查 MySQL");
        assertEquals(2L, QdrantVectorStoreFactory.countPoints(client, props), "两个片段两个点");
    }

    @Test
    void filtersOutOtherEmbeddingModels() {
        KnowledgeDocument document = doc(2L, "data/documents/other.docx", "other.docx");
        index.index(document, List.of(
                chunk(2L, 0, null, "这一段用的是换模型前写入的向量。", "other-model@1024"),
                chunk(2L, 1, null, "这一段用的是当前模型写入的向量。", MODEL_ID)));

        List<KnowledgeVectorIndex.Scored> hits = index.searchVector(vector("这一段用的是当前模型写入的向量。"), MODEL_ID, 10);

        assertFalse(hits.isEmpty());
        assertTrue(hits.stream().allMatch(hit -> MODEL_ID.equals(hit.payload().embeddingModel())),
                "embedding_model 过滤必须真在 Qdrant 侧生效，不能靠客户端兜");
    }

    @Test
    void removesDocumentPointsByFilter() {
        index.index(doc(3L, "data/documents/drop.docx", "drop.docx"),
                List.of(chunk(3L, 0, null, "这份文档马上要被删除。", MODEL_ID),
                        chunk(3L, 1, null, "连同它的第二个片段一起删除。", MODEL_ID)));
        index.index(doc(4L, "data/documents/keep.docx", "keep.docx"),
                List.of(chunk(4L, 0, null, "这份文档要留着。", MODEL_ID)));
        assertEquals(3L, QdrantVectorStoreFactory.countPoints(client, props));

        index.removeDocument(3L);

        assertEquals(1L, QdrantVectorStoreFactory.countPoints(client, props), "按 document_id 过滤删除应生效");
        assertTrue(index.searchVector(vector("这份文档马上要被删除。"), MODEL_ID, 5).stream()
                .noneMatch(hit -> hit.payload().documentId() == 3L));
    }

    @Test
    void replaceDeletesStaleChunksByFilter() {
        KnowledgeDocument document = doc(6L, "data/documents/revised.docx", "revised.docx");
        index.index(document, List.of(
                chunk(6L, 0, null, "第一版：这份文档有三个片段。", MODEL_ID),
                chunk(6L, 1, null, "第一版：第二个片段。", MODEL_ID),
                chunk(6L, 2, null, "第一版：第三个片段。", MODEL_ID)));
        assertEquals(3L, QdrantVectorStoreFactory.countPoints(client, props));

        index.replace(document, List.of(chunk(6L, 0, null, "第二版：改成只有一个片段。", MODEL_ID)));

        assertEquals(1L, QdrantVectorStoreFactory.countPoints(client, props),
                "覆盖时要按 document_id 清掉旧点，只留新片段");
    }

    @Test
    void rebuildSkipsBackfillWhenCollectionAlreadyHasPoints() {
        List<KnowledgeChunk> chunks = List.of(
                chunk(5L, 0, null, "内置资产：简历评价标准分四档。", MODEL_ID),
                chunk(5L, 1, null, "内置资产：模板分学术与商务两类。", MODEL_ID));
        doc(5L, "resume-builder/references/rules.md", "rules.md");

        index.rebuild(chunks, documents);
        assertEquals(2L, QdrantVectorStoreFactory.countPoints(client, props), "空集合应回灌");

        index.rebuild(chunks, documents);
        assertEquals(2L, QdrantVectorStoreFactory.countPoints(client, props), "已有数据时不应重复灌入");
    }
}