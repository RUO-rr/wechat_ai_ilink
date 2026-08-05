package io.github.wangyangxu.ailink.service;

import io.github.wangyangxu.ailink.mapper.AgentMemoryMapper;
import io.github.wangyangxu.ailink.model.AgentMemory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryServiceTest {

    @Mock
    private AgentMemoryMapper memoryMapper;

    @InjectMocks
    private MemoryService memoryService;

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
    }

    @Test
    void recordExtracted_skip_doesNotWrite() {
        memoryService.recordExtracted("u1", null, List.of(
                new MemoryService.ExtractedMemory("fact", "answer_style", "重复内容", "skip", "raw")));
        verify(memoryMapper, never()).insert(any());
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
    }

    @Test
    void deleteNote_softDeletes() {
        memoryService.deleteNote("u1", 3L);
        verify(memoryMapper).markDeleted(3L, "u1");
    }

    @Test
    void getInjection_populatesThreeIndependentSlots() {
        AgentMemory summary = new AgentMemory("u1", "summary", "history", "摘要S", null, "active", null);
        AgentMemory fact = new AgentMemory("u1", "fact", "answer_style", "喜欢简洁", null, "active", null);
        AgentMemory pref = new AgentMemory("u1", "preference", "timezone", "在北京", null, "active", null);
        AgentMemory note = new AgentMemory("u1", "note", "user_note", "周五交报告", null, "active", null);

        when(memoryMapper.findLatestActiveSummary("u1")).thenReturn(summary);
        when(memoryMapper.findActiveFacts("u1", 5)).thenReturn(List.of(fact, pref));
        when(memoryMapper.findActiveNotes("u1", 3)).thenReturn(List.of(note));

        MemoryService.MemoryInjection injection = memoryService.getInjection("u1");
        assertEquals("摘要S", injection.summary());
        assertEquals(2, injection.facts().size());
        assertEquals(1, injection.notes().size());
    }
}
