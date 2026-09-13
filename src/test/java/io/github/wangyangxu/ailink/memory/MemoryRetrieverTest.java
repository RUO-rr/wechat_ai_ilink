package io.github.wangyangxu.ailink.memory;

import io.github.wangyangxu.ailink.mapper.AgentMemoryMapper;
import io.github.wangyangxu.ailink.model.AgentMemory;
import io.github.wangyangxu.ailink.rag.HashingEmbeddingModel;
import io.github.wangyangxu.ailink.service.MetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryRetrieverTest {

    private static final Set<String> FACT_TYPES = Set.of("fact", "preference");
    private static final Set<String> NOTE_TYPES = Set.of("note");

    private final AgentMemoryMapper memoryMapper = mock(AgentMemoryMapper.class);
    private final HashingEmbeddingModel embedder = new HashingEmbeddingModel(256);
    private final MemoryProperties props = new MemoryProperties();
    private final MetricsService metrics = new MetricsService();
    private final MemoryIndex index = new MemoryIndex(memoryMapper, embedder, props);
    private final MemoryRetriever retriever = new MemoryRetriever(props, index, metrics);

    private static AgentMemory memory(long id, String type, String dimension, String content) {
        AgentMemory m = new AgentMemory("u1", type, dimension, content, null, "active", null);
        m.setId(id);
        return m;
    }

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(props, "recallEnabled", true);
        ReflectionTestUtils.setField(props, "vectorWeight", 0.6d);
        ReflectionTestUtils.setField(props, "candidateMultiplier", 3);
        ReflectionTestUtils.setField(props, "maxEntriesPerUser", 500);
        ReflectionTestUtils.setField(props, "dedupeEnabled", true);
        ReflectionTestUtils.setField(props, "dedupeThreshold", 0.92d);
    }

    private void stubMemories() {
        when(memoryMapper.findIndexableByUser("u1", 500)).thenReturn(List.of(
                memory(1L, "fact", "answer_style", "用户喜欢简洁回答，不要长篇大论。"),
                memory(2L, "preference", "project", "用户在做微信机器人项目，主要用 Java 和 Spring Boot。"),
                memory(3L, "note", "user_note", "周五前把季度报告交上去。")));
    }

    @Test
    void recallRanksRelevantMemoryFirst() {
        stubMemories();

        List<MemoryEntry> hits = retriever.recall("u1", "我那个机器人项目的技术栈是什么", FACT_TYPES, 3);

        assertFalse(hits.isEmpty());
        assertEquals(2L, hits.get(0).id(), "相关记忆应排在无关记忆之前，而不是按新旧排");
    }

    @Test
    void recallFiltersByMemoryType() {
        stubMemories();

        List<MemoryEntry> notes = retriever.recall("u1", "季度报告", NOTE_TYPES, 3);

        assertEquals(1, notes.size());
        assertEquals(3L, notes.get(0).id());
    }

    @Test
    void recallReturnsEmptyWhenIndexCannotBeLoaded() {
        when(memoryMapper.findIndexableByUser("u1", 500)).thenThrow(new IllegalStateException("db down"));

        assertTrue(retriever.recall("u1", "任意问题", FACT_TYPES, 3).isEmpty());
        assertFalse(retriever.isReady("u1"), "装载失败时读路径应退回 recency");
    }

    @Test
    void recallReturnsEmptyWhenDisabled() {
        ReflectionTestUtils.setField(props, "recallEnabled", false);

        assertTrue(retriever.recall("u1", "任意问题", FACT_TYPES, 3).isEmpty());
        verify(memoryMapper, never()).findIndexableByUser("u1", 500);
        assertFalse(retriever.isReady("u1"));
    }

    @Test
    void recallReturnsEmptyForBlankQuery() {
        stubMemories();

        assertTrue(retriever.recall("u1", "   ", FACT_TYPES, 3).isEmpty());
        assertTrue(retriever.recall("u1", null, FACT_TYPES, 3).isEmpty());
    }

    @Test
    void isReadyReflectsLoadedState() {
        stubMemories();
        assertFalse(retriever.isReady("u1"));

        retriever.recall("u1", "季度报告", NOTE_TYPES, 3);

        assertTrue(retriever.isReady("u1"));
    }

    @Test
    void isDuplicateIsTrueForSameContent() {
        stubMemories();

        assertTrue(retriever.isDuplicate("u1", "用户喜欢简洁回答，不要长篇大论。"));
    }

    @Test
    void isDuplicateIsFalseForUnrelatedContent() {
        stubMemories();

        assertFalse(retriever.isDuplicate("u1", "用户开通了企业微信的会话存档权限。"));
    }

    @Test
    void isDuplicateIsSkippedWhenDisabled() {
        ReflectionTestUtils.setField(props, "dedupeEnabled", false);

        assertFalse(retriever.isDuplicate("u1", "用户喜欢简洁回答，不要长篇大论。"));
        verify(memoryMapper, never()).findIndexableByUser("u1", 500);
    }
}