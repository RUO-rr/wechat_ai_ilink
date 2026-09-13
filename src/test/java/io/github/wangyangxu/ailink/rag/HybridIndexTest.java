package io.github.wangyangxu.ailink.rag;

import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridIndexTest {

    private static final String MODEL_ID = HashingEmbeddingModel.MODEL_NAME;

    private final HashingEmbeddingModel embedder = new HashingEmbeddingModel(256);
    private final HybridIndex<String> index = new HybridIndex<>();

    private float[] vector(String text) {
        return embedder.embedAll(List.of(TextSegment.from(text))).content().get(0).vector();
    }

    private HybridIndex.Doc<String> doc(String key, String text) {
        return new HybridIndex.Doc<>(key, text, vector(text), MODEL_ID, key);
    }

    @BeforeEach
    void setUp() {
        index.replace(List.of(
                doc("a", "简历写作要突出量化结果，避免形容词堆砌。"),
                doc("b", "岗位调研要收集招聘 JD 与行业动态。")));
    }

    @Test
    void keywordSearchRanksLiteralHitFirst() {
        List<HybridIndex.Scored<String>> hits = index.searchKeyword("招聘 JD 行业动态", 5);

        assertFalse(hits.isEmpty());
        assertEquals("b", hits.get(0).payload());
        assertTrue(hits.get(0).score() > 0d);
    }

    @Test
    void vectorSearchRanksClosestEntryFirst() {
        List<HybridIndex.Scored<String>> hits = index.searchVector(vector("简历写作要突出量化结果"), MODEL_ID, 5);

        assertFalse(hits.isEmpty());
        assertEquals("a", hits.get(0).payload());
    }

    @Test
    void vectorSearchIsolatesEmbeddingSpaces() {
        float[] query = vector("简历写作要突出量化结果");

        assertFalse(index.searchVector(query, MODEL_ID, 5).isEmpty());
        assertTrue(index.searchVector(query, "other-model@256", 5).isEmpty(),
                "不同向量模型的空间不互通，降级向量不会污染真实语义检索");
    }

    @Test
    void vectorSearchSkipsDimensionMismatch() {
        float[] otherDimension = embedder.embedAll(List.of(TextSegment.from("简历写作"))).content().get(0).vector();
        float[] shorter = new float[otherDimension.length - 1];

        assertTrue(index.searchVector(shorter, MODEL_ID, 5).isEmpty());
    }

    @Test
    void updateReplacesEntryWithSameKey() {
        HybridIndex.Doc<String> replacement = doc("a", "新版：简历写作要突出个人贡献占比。");

        index.update(docs -> {
            List<HybridIndex.Doc<String>> merged = new ArrayList<>();
            for (HybridIndex.Doc<String> doc : docs) {
                if (!doc.key().equals("a")) {
                    merged.add(doc);
                }
            }
            merged.add(replacement);
            return merged;
        });

        assertEquals(2, index.size(), "同 key 覆盖，不新增条目");
        assertTrue(index.searchKeyword("个人贡献占比", 5).stream()
                .anyMatch(hit -> hit.payload().equals("a")));
    }

    @Test
    void statsReportSizeAndVectorizedCount() {
        HybridIndex.Stats stats = index.stats();

        assertEquals(2, stats.size());
        assertEquals(2, stats.vectorized());
        assertEquals(256, stats.dimensions());
    }

    @Test
    void entriesWithoutVectorStillSearchableByKeyword() {
        HybridIndex<String> keywordOnly = new HybridIndex<>();
        keywordOnly.replace(List.of(new HybridIndex.Doc<>("k", "长期记忆去重", null, null, "k")));

        HybridIndex.Stats stats = keywordOnly.stats();

        assertEquals(1, stats.size());
        assertEquals(0, stats.vectorized());
        assertFalse(keywordOnly.searchKeyword("长期记忆去重", 5).isEmpty());
        assertTrue(keywordOnly.searchVector(new float[]{1f}, MODEL_ID, 5).isEmpty());
    }

    @Test
    void emptyQueryOrNullVectorReturnsNothing() {
        assertTrue(index.searchKeyword("  ", 5).isEmpty());
        assertTrue(index.searchKeyword(null, 5).isEmpty());
        assertTrue(index.searchVector(null, MODEL_ID, 5).isEmpty());
        assertTrue(index.searchVector(new float[]{1f}, MODEL_ID, 0).isEmpty());
    }

    @Test
    void emptyIndexReportsEmpty() {
        HybridIndex<String> empty = new HybridIndex<>();

        assertTrue(empty.isEmpty());
        assertEquals(0, empty.size());
        assertTrue(empty.searchKeyword("任意词", 5).isEmpty());
        assertTrue(empty.docs().isEmpty());
    }
}