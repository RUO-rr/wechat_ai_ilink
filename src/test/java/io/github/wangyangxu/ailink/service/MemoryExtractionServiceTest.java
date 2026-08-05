package io.github.wangyangxu.ailink.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.wangyangxu.ailink.client.LlmClient;
import io.github.wangyangxu.ailink.mapper.AgentMemoryMapper;
import io.github.wangyangxu.ailink.mapper.ChatMessageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryExtractionServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mock
    private LlmClient llmClient;

    @Mock
    private MemoryService memoryService;

    @Mock
    private AgentMemoryMapper memoryMapper;

    @Mock
    private ChatMessageMapper chatMessageMapper;

    @InjectMocks
    private MemoryExtractionService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "sampleRate", 1.0);
        ReflectionTestUtils.setField(service, "baseUrl", "http://example.com");
        ReflectionTestUtils.setField(service, "model", "test-model");
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
    }

    @Test
    void extract_validJson_recordsEntriesWithSourceId() throws Exception {
        String llmContent = "[{\"memory_type\":\"fact\",\"dimension\":\"answer_style\","
                + "\"content\":\"用户喜欢简洁回答\",\"conflict_action\":\"new\"}]";
        JsonNode root = OBJECT_MAPPER.readTree(
                "{\"choices\":[{\"message\":{\"content\":" + OBJECT_MAPPER.writeValueAsString(llmContent) + "}}]}");
        when(llmClient.callChatApi(any(), any(), any())).thenReturn(root);
        when(memoryMapper.findActiveFacts("u1", 20)).thenReturn(List.of());
        when(chatMessageMapper.findLatestId("bot1", "u1")).thenReturn(99L);

        service.extract("bot1", "u1", List.of(Map.of("role", "user", "content", "我喜欢简洁的回答")));

        verify(memoryService).recordExtracted(eq("u1"), eq(99L), anyList());
    }

    @Test
    void extract_invalidJson_skipsRound() throws Exception {
        JsonNode root = OBJECT_MAPPER.readTree(
                "{\"choices\":[{\"message\":{\"content\":\"这不是合法的JSON\"}}]}");
        when(llmClient.callChatApi(any(), any(), any())).thenReturn(root);
        when(memoryMapper.findActiveFacts("u1", 20)).thenReturn(List.of());

        service.extract("bot1", "u1", List.of(Map.of("role", "user", "content", "你好")));

        verify(memoryService, never()).recordExtracted(any(), any(), anyList());
    }

    @Test
    void extract_disabled_doesNothing() throws Exception {
        ReflectionTestUtils.setField(service, "enabled", false);
        service.extract("bot1", "u1", List.of(Map.of("role", "user", "content", "你好")));
        verify(llmClient, never()).callChatApi(any(), any(), any());
    }

    @Test
    void parse_codeFencedJson_extractsEntry() {
        String content = "```json\n[{\"memory_type\":\"preference\",\"dimension\":\"timezone\","
                + "\"content\":\"用户在北京\",\"conflict_action\":\"new\"}]\n```";
        List<MemoryService.ExtractedMemory> entries = service.parse(content);
        assertEquals(1, entries.size());
        assertEquals("preference", entries.get(0).memoryType());
        assertEquals("timezone", entries.get(0).dimension());
    }

    @Test
    void parse_mixedGoodAndBad_keepsGoodEntry() {
        String content = "[{\"memory_type\":\"fact\",\"dimension\":\"answer_style\","
                + "\"content\":\"喜欢简洁\",\"conflict_action\":\"new\"},"
                + "{\"memory_type\":\"fact\",\"dimension\":\"timezone\",\"content\":\"在北京\",\"conflict_action\":\"new\"}]";
        List<MemoryService.ExtractedMemory> entries = service.parse(content);
        // 字段缺失的坏条目由 MemoryService.recordExtracted 逐条丢弃，parse 阶段原样保留
        assertEquals(2, entries.size());
    }
}
