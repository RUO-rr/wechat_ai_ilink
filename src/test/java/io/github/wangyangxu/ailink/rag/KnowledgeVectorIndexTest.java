package io.github.wangyangxu.ailink.rag;

import dev.langchain4j.data.segment.TextSegment;
import io.github.wangyangxu.ailink.model.KnowledgeChunk;
import io.github.wangyangxu.ailink.model.KnowledgeDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeVectorIndexTest {

    private static final String MODEL_ID = HashingEmbeddingModel.MODEL_NAME;

    private final HashingEmbeddingModel embedder = new HashingEmbeddingModel(256);
    private final KnowledgeVectorIndex index = new KnowledgeVectorIndex();

    private final Map<Long, KnowledgeDocument> documents = new HashMap<>();
    private KnowledgeDocument doc(long id, String path) {
        KnowledgeDocument document = new KnowledgeDocument(KnowledgeDocument.SOURCE_RESOURCE, path, path,
                "hash-" + id, MODEL_ID, 256);
        document.setId(id);
        documents.put(id, document);
        return document;
    }

    private KnowledgeChunk chunk(long docId, int index, String heading, String text) {
        float[] vector = embedder.embedAll(List.of(TextSegment.from(text))).content().get(0).vector();
        return new KnowledgeChunk(docId, index, heading, text, EmbeddingCodec.encode(vector), vector.length, MODEL_ID);
    }

    @BeforeEach
    void setUp() {
        doc(1L, "resume-builder/references/resume_writing_rules.md");
        doc(2L, "resume-builder/references/online_role_research.md");
        index.rebuild(List.of(
                chunk(1L, 0, "写作规则", "简历写作要突出量化结果，避免形容词堆砌。"),
                chunk(2L, 0, "岗位调研", "岗位调研需要收集招聘 JD 与行业动态信息。")
        ), documents);
    }

    private float[] queryVector(String text) {
        return embedder.embedAll(List.of(TextSegment.from(text))).content().get(0).vector();
    }

    @Test
    void vectorSearchRanksClosestChunkFirst() {
        List<KnowledgeVectorIndex.Scored> hits = index.searchVector(queryVector("简历写作怎么突出量化结果"), MODEL_ID, 5);

        assertFalse(hits.isEmpty());
        assertEquals("resume-builder/references/resume_writing_rules.md", hits.get(0).entry().sourcePath());
    }

    @Test
    void keywordSearchFindsLiteralTerm() {
        List<KnowledgeVectorIndex.Scored> hits = index.searchKeyword("招聘 JD 行业动态", 5);

        assertFalse(hits.isEmpty());
        assertEquals("resume-builder/references/online_role_research.md", hits.get(0).entry().sourcePath());
        assertTrue(hits.get(0).score() > 0);
    }

    @Test
    void vectorSearchIsolatesEmbeddingSpaces() {
        List<KnowledgeVectorIndex.Scored> sameSpace =
                index.searchVector(queryVector("简历写作要突出量化结果"), MODEL_ID, 5);
        assertFalse(sameSpace.isEmpty());
        assertTrue(sameSpace.stream().allMatch(s -> MODEL_ID.equals(s.entry().embeddingModel())));
        assertTrue(index.searchVector(queryVector("简历写作要突出量化结果"), "other-model@1024", 5).isEmpty(),
                "不同向量模型的空间不互通，避免降级向量污染真实语义检索");
    }

    @Test
    void upsertReplacesChunksOfSameDocumentOnly() {
        index.upsert(documents.get(1L), List.of(
                chunk(1L, 0, "写作规则", "新版本：简历写作要突出量化结果与个人贡献占比。"),
                chunk(1L, 1, "写作规则", "补充：避免无信息量的形容词。")
        ));

        assertEquals(3, index.size(), "1 号文档的旧片段应被替换，2 号文档不受影响");
        assertEquals(2, index.entriesOfDocument(1L).size());
    }

    @Test
    void removeDocumentDropsItsChunks() {
        index.removeDocument(1L);

        assertEquals(1, index.size());
        assertTrue(index.entriesOfDocument(1L).isEmpty());
    }

    @Test
    void statsReportVectorizedChunks() {
        KnowledgeVectorIndex.Stats stats = index.stats();

        assertEquals(2, stats.documents());
        assertEquals(2, stats.chunks());
        assertEquals(2, stats.vectorizedChunks());
        assertEquals(256, stats.dimensions());
    }

    @Test
    void emptyQueryReturnsNothing() {
        assertTrue(index.searchKeyword("  ", 5).isEmpty());
        assertTrue(index.searchVector(null, MODEL_ID, 5).isEmpty());
    }
}