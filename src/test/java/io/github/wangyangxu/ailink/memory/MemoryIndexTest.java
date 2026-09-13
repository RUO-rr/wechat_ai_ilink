package io.github.wangyangxu.ailink.memory;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.github.wangyangxu.ailink.mapper.AgentMemoryMapper;
import io.github.wangyangxu.ailink.model.AgentMemory;
import io.github.wangyangxu.ailink.rag.HashingEmbeddingModel;
import io.github.wangyangxu.ailink.rag.HybridIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryIndexTest {

    private final AgentMemoryMapper memoryMapper = mock(AgentMemoryMapper.class);
    private final HashingEmbeddingModel embedder = new HashingEmbeddingModel(256);
    private final MemoryProperties props = new MemoryProperties();
    private final MemoryIndex index = new MemoryIndex(memoryMapper, embedder, props);

    private static AgentMemory memory(long id, String type, String content) {
        AgentMemory m = new AgentMemory("u1", type, type.equals("note") ? "user_note" : "answer_style",
                content, null, "active", null);
        m.setId(id);
        return m;
    }

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(props, "maxEntriesPerUser", 500);
    }

    /** 每个用例只声明自己用得到的桩，避免严格模式下出现「未被使用的桩」 */
    private void stubActiveMemories() {
        when(memoryMapper.findIndexableByUser("u1", 500)).thenReturn(List.of(
                memory(1L, "fact", "用户喜欢简洁回答，不要长篇大论。"),
                memory(2L, "note", "周五前把季度报告交上去。")));
    }

    @Test
    void ensureLoadedBuildsIndexFromActiveMemories() {
        stubActiveMemories();
        assertTrue(index.ensureLoaded("u1"));
        assertTrue(index.isLoaded("u1"));

        List<HybridIndex.Scored<MemoryEntry>> hits = index.searchKeyword("u1", "季度报告", 5);

        assertFalse(hits.isEmpty());
        assertEquals(2L, hits.get(0).payload().id());
    }

    @Test
    void ensureLoadedIsIdempotent() {
        stubActiveMemories();
        index.ensureLoaded("u1");
        index.ensureLoaded("u1");

        verify(memoryMapper, times(1)).findIndexableByUser("u1", 500);
    }

    @Test
    void ensureLoadedReturnsFalseAndDoesNotCacheWhenRepositoryFails() {
        when(memoryMapper.findIndexableByUser("u2", 500)).thenThrow(new IllegalStateException("db down"));

        assertFalse(index.ensureLoaded("u2"));
        assertFalse(index.isLoaded("u2"), "失败不缓存，下一条消息会重试");
    }

    @Test
    void vectorSearchFindsSemanticallyNearMemory() {
        stubActiveMemories();
        index.ensureLoaded("u1");
        float[] query = embedder.embedAll(List.of(TextSegment.from("回答别太长"))).content().get(0).vector();

        List<HybridIndex.Scored<MemoryEntry>> hits = index.searchVector("u1", query, 5);

        assertFalse(hits.isEmpty());
        assertEquals(1L, hits.get(0).payload().id());
    }

    @Test
    void upsertReplacesEntryWithSameId() {
        stubActiveMemories();
        index.ensureLoaded("u1");

        AgentMemory updated = memory(1L, "fact", "用户喜欢详细解释，最好带例子。");
        index.upsert(updated);

        assertEquals(1, index.searchKeyword("u1", "带例子", 5).size());
        assertTrue(index.searchKeyword("u1", "长篇大论", 5).isEmpty(), "旧内容已被同 id 覆盖");
        assertEquals(1, index.searchKeyword("u1", "季度报告", 5).size(), "另一条记忆不受影响");
    }

    @Test
    void upsertIsIgnoredWhenIndexNotLoaded() {
        index.upsert(memory(3L, "fact", "新增记忆"));

        assertFalse(index.isLoaded("u1"));
        assertTrue(index.searchKeyword("u1", "新增记忆", 5).isEmpty(),
                "索引未装载时不建部分索引，交给下一次装载从库里补齐");
    }

    @Test
    void removeDropsEntry() {
        stubActiveMemories();
        index.ensureLoaded("u1");

        index.remove("u1", 2L);

        assertTrue(index.searchKeyword("u1", "季度报告", 5).isEmpty());
        assertFalse(index.searchKeyword("u1", "简洁回答", 5).isEmpty());
    }

    @Test
    void duplicateContentIsDetectedByCosineSimilarity() {
        stubActiveMemories();
        index.ensureLoaded("u1");
        float[] vector = embedder.embedAll(List.of(TextSegment.from("用户喜欢简洁回答，不要长篇大论。")))
                .content().get(0).vector();

        List<HybridIndex.Scored<MemoryEntry>> hits = index.searchVector("u1", vector, 1);

        assertEquals(1.0d, hits.get(0).score(), 1e-6, "余弦为绝对值，可直接用于判重阈值");
    }

    @Test
    void embeddingFailureDegradesToKeywordOnly() {
        EmbeddingModel broken = new EmbeddingModel() {
            @Override
            public int dimension() { return 256; }

            @Override
            public String modelName() { return "broken"; }

            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                throw new IllegalStateException("向量服务不可用");
            }
        };
        MemoryIndex degraded = new MemoryIndex(memoryMapper, broken, props);

        stubActiveMemories();
        assertTrue(degraded.ensureLoaded("u1"), "向量化失败不影响装载");
        assertFalse(degraded.searchKeyword("u1", "季度报告", 5).isEmpty(), "关键词通道照常工作");
        assertTrue(degraded.searchVector("u1", new float[256], 5).isEmpty());
    }

    @Test
    void embeddingModelIdIsolatesFallbackSpace() {
        assertEquals(HashingEmbeddingModel.MODEL_NAME, index.embeddingModelId());
    }
}