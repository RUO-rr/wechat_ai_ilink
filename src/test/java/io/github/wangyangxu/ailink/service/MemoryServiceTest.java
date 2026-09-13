package io.github.wangyangxu.ailink.service;

import io.github.wangyangxu.ailink.mapper.AgentMemoryMapper;
import io.github.wangyangxu.ailink.memory.MemoryEntry;
import io.github.wangyangxu.ailink.memory.MemoryIndex;
import io.github.wangyangxu.ailink.memory.MemoryProperties;
import io.github.wangyangxu.ailink.memory.MemoryRetriever;
import io.github.wangyangxu.ailink.model.AgentMemory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryServiceTest {

    private static final Set<String> FACT_TYPES = Set.of("fact", "preference");
    private static final Set<String> NOTE_TYPES = Set.of("note");

    @Mock
    private AgentMemoryMapper memoryMapper;

    @Mock
    private MemoryIndex memoryIndex;

    @Mock
    private MemoryRetriever memoryRetriever;

    @Mock
    private MemoryProperties memoryProps;

    @InjectMocks
    private MemoryService memoryService;

    private static AgentMemory memory(long id, String type, String dimension, String content) {
        AgentMemory m = new AgentMemory("u1", type, dimension, content, null, "active", null);
        m.setId(id);
        return m;
    }

    @Test
    void normalizeDimension_trimsAndLowercases() {
        assertEquals("answer_style", MemoryService.normalizeDimension("  Answer_Style  "));
        assertEquals("timezone", MemoryService.normalizeDimension("TIMEZONE"));
        assertNull(MemoryService.normalizeDimension(null));
    }

    @Test
    void recordExtracted_validNewEntry_insertsNormalizedActiveMemory() {
        memoryService.recordExtracted("u1", 42L, List.of(
                new MemoryService.ExtractedMemory("fact", " Answer_Style ", "用户喜欢简洁回答", "new", "raw")));

        ArgumentCaptor<AgentMemory> captor = ArgumentCaptor.forClass(AgentMemory.class);
        verify(memoryMapper).insert(captor.capture());
        AgentMemory m = captor.getValue();
        assertEquals("u1", m.getUserId());
        assertEquals("fact", m.getMemoryType());
        assertEquals("answer_style", m.getDimension());
        assertEquals("active", m.getStatus());
        assertEquals(42L, m.getSourceMessageId());
        assertNull(m.getSupersedesId());
        verify(memoryIndex).upsert(m);
    }

    @Test
    void recordExtracted_badEntriesSkipped_goodEntryKept() {
        memoryService.recordExtracted("u1", null, List.of(
                new MemoryService.ExtractedMemory("fact", " ", "内容", "new", "bad-dimension"),
                new MemoryService.ExtractedMemory("fact", "timezone", "", "new", "bad-content"),
                new MemoryService.ExtractedMemory("fact", "timezone", "内容", "unknown-action", "bad-action"),
                new MemoryService.ExtractedMemory("preference", "timezone", "用户在北京", "new", "good")));

        ArgumentCaptor<AgentMemory> captor = ArgumentCaptor.forClass(AgentMemory.class);
        verify(memoryMapper, times(1)).insert(captor.capture());
        assertEquals("timezone", captor.getValue().getDimension());
        assertEquals("preference", captor.getValue().getMemoryType());
    }

    @Test
    void recordExtracted_supersede_marksOldAndLinksNew() {
        AgentMemory old = new AgentMemory("u1", "fact", "answer_style", "旧偏好", null, "active", null);
        old.setId(7L);
        when(memoryMapper.findLatestActiveByDimension("u1", "answer_style")).thenReturn(old);

        memoryService.recordExtracted("u1", null, List.of(
                new MemoryService.ExtractedMemory("preference", "answer_style", "用户喜欢详细回答", "supersede", "raw")));

        verify(memoryMapper).markSuperseded(7L);
        ArgumentCaptor<AgentMemory> captor = ArgumentCaptor.forClass(AgentMemory.class);
        verify(memoryMapper).insert(captor.capture());
        AgentMemory m = captor.getValue();
        assertEquals("preference", m.getMemoryType());
        assertEquals("active", m.getStatus());
        assertEquals(7L, m.getSupersedesId());
        verify(memoryIndex).remove("u1", 7L);
        verify(memoryIndex).upsert(m);
    }

    @Test
    void recordExtracted_skip_doesNotWrite() {
        memoryService.recordExtracted("u1", null, List.of(
                new MemoryService.ExtractedMemory("fact", "answer_style", "重复内容", "skip", "raw")));
        verify(memoryMapper, never()).insert(any());
    }

    @Test
    void recordExtracted_newButSemanticallyDuplicate_isSkipped() {
        when(memoryRetriever.isDuplicate("u1", "用户喜欢简洁回答")).thenReturn(true);

        memoryService.recordExtracted("u1", null, List.of(
                new MemoryService.ExtractedMemory("fact", "reply_style", "用户喜欢简洁回答", "new", "raw")));

        verify(memoryMapper, never()).insert(any());
        verify(memoryIndex, never()).upsert(any());
    }

    @Test
    void recordExtracted_supersede_isNotDeduplicated() {
        when(memoryMapper.findLatestActiveByDimension("u1", "timezone")).thenReturn(null);

        memoryService.recordExtracted("u1", null, List.of(
                new MemoryService.ExtractedMemory("fact", "timezone", "用户在上海", "supersede", "raw")));

        verify(memoryMapper).insert(any(AgentMemory.class));
        verify(memoryRetriever, never()).isDuplicate(any(), any());
    }

    @Test
    void recordSummary_supersedesExistingSummary() {
        AgentMemory old = new AgentMemory("u1", "summary", "history", "旧摘要", null, "active", null);
        old.setId(5L);
        when(memoryMapper.findLatestActiveByDimension("u1", "history")).thenReturn(old);

        memoryService.recordSummary("u1", "新摘要内容");

        verify(memoryMapper).markSuperseded(5L);
        ArgumentCaptor<AgentMemory> captor = ArgumentCaptor.forClass(AgentMemory.class);
        verify(memoryMapper).insert(captor.capture());
        assertEquals("summary", captor.getValue().getMemoryType());
        assertEquals("history", captor.getValue().getDimension());
        assertEquals(5L, captor.getValue().getSupersedesId());
        verify(memoryIndex, never()).upsert(any());
    }

    @Test
    void addNote_writesAndIndexes() {
        Long noteId = memoryService.addNote("u1", "周五交报告");

        ArgumentCaptor<AgentMemory> captor = ArgumentCaptor.forClass(AgentMemory.class);
        verify(memoryMapper).insert(captor.capture());
        verify(memoryIndex).upsert(captor.getValue());
        assertNull(noteId);
    }

    @Test
    void deleteNote_softDeletesAndDropsFromIndex() {
        memoryService.deleteNote("u1", 3L);

        verify(memoryMapper).markDeleted(3L, "u1");
        verify(memoryIndex).remove("u1", 3L);
    }

    @Test
    void deleteNote_withoutId_doesNothing() {
        assertFalse(memoryService.deleteNote("u1", null));
        verify(memoryMapper, never()).markDeleted(anyLong(), any());
    }

    @Test
    void getInjection_populatesThreeIndependentSlots() {
        AgentMemory summary = new AgentMemory("u1", "summary", "history", "摘要S", null, "active", null);
        AgentMemory fact = new AgentMemory("u1", "fact", "answer_style", "喜欢简洁", null, "active", null);
        AgentMemory pref = new AgentMemory("u1", "preference", "timezone", "在北京", null, "active", null);
        AgentMemory note = new AgentMemory("u1", "note", "user_note", "周五交报告", null, "active", null);
        fact.setId(1L);
        pref.setId(2L);
        note.setId(3L);

        when(memoryMapper.findLatestActiveSummary("u1")).thenReturn(summary);
        when(memoryProps.getRecentFactGuarantee()).thenReturn(2);
        when(memoryProps.getRecentNoteGuarantee()).thenReturn(1);
        when(memoryMapper.findActiveFacts("u1", 2)).thenReturn(List.of(fact, pref));
        when(memoryMapper.findActiveNotes("u1", 1)).thenReturn(List.of(note));

        MemoryService.MemoryInjection injection = memoryService.getInjection("u1", "帮我看下简历");

        assertEquals("摘要S", injection.summary());
        assertEquals(2, injection.facts().size());
        assertEquals("[fact/answer_style] 喜欢简洁", injection.facts().get(0));
        assertEquals(1, injection.notes().size());
        assertEquals("周五交报告", injection.notes().get(0));
    }

    @Test
    void getInjection_appendsRecalledMemoryBeyondRecencyGuarantee() {
        AgentMemory recent = memory(1L, "fact", "answer_style", "喜欢简洁");
        AgentMemory relevant = memory(9L, "preference", "project", "正在做微信机器人项目");

        when(memoryMapper.findLatestActiveSummary("u1")).thenReturn(null);
        when(memoryProps.getRecentFactGuarantee()).thenReturn(1);
        when(memoryMapper.findActiveFacts("u1", 1)).thenReturn(List.of(recent));
        when(memoryRetriever.isReady("u1")).thenReturn(true);
        when(memoryRetriever.recall("u1", "我那个项目的架构是什么", FACT_TYPES, 5))
                .thenReturn(List.of(MemoryEntry.from(relevant)));

        MemoryService.MemoryInjection injection =
                memoryService.getInjection("u1", "我那个项目的架构是什么");

        assertEquals(2, injection.facts().size(), "保底 1 条 + 召回 1 条");
        assertEquals("[fact/answer_style] 喜欢简洁", injection.facts().get(0), "保底记忆排在最前，顺序稳定");
        assertEquals("[preference/project] 正在做微信机器人项目", injection.facts().get(1));
    }

    @Test
    void getInjection_fallsBackToRecencyWhenIndexNotReady() {
        AgentMemory f1 = memory(1L, "fact", "answer_style", "喜欢简洁");
        AgentMemory f2 = memory(2L, "fact", "timezone", "在北京");

        when(memoryMapper.findLatestActiveSummary("u1")).thenReturn(null);
        when(memoryProps.getRecentFactGuarantee()).thenReturn(1);
        when(memoryMapper.findActiveFacts("u1", 1)).thenReturn(List.of(f1));
        when(memoryRetriever.isReady("u1")).thenReturn(false);
        when(memoryMapper.findActiveFacts("u1", 5)).thenReturn(List.of(f1, f2));

        MemoryService.MemoryInjection injection = memoryService.getInjection("u1", "随便聊聊");

        assertEquals(2, injection.facts().size(), "索引不可用时补齐最近记忆，注入内容不因检索失败而变少");
    }

    @Test
    void getInjection_respectsFactQuotaWhenRecallOverflows() {
        when(memoryMapper.findLatestActiveSummary("u1")).thenReturn(null);
        when(memoryProps.getRecentFactGuarantee()).thenReturn(1);
        when(memoryMapper.findActiveFacts("u1", 1)).thenReturn(List.of(memory(1L, "fact", "a", "保底")));
        when(memoryRetriever.isReady("u1")).thenReturn(true);

        List<MemoryEntry> recalled = new java.util.ArrayList<>();
        for (long i = 10; i < 20; i++) {
            recalled.add(MemoryEntry.from(memory(i, "fact", "dim" + i, "召回" + i)));
        }
        when(memoryRetriever.recall("u1", "查询", FACT_TYPES, 5)).thenReturn(recalled);

        MemoryService.MemoryInjection injection = memoryService.getInjection("u1", "查询");

        assertEquals(5, injection.facts().size(), "记忆槽上限 5 条");
    }
}