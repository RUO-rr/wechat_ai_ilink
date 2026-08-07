package io.github.wangyangxu.ailink.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.wangyangxu.ailink.mapper.ChatMessageMapper;
import io.github.wangyangxu.ailink.model.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationHistoryTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String KEY = "chat:history:legacy:u1";
    private static final Duration TTL = Duration.ofMinutes(30);

    @Mock
    private ChatMessageMapper chatMessageMapper;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private ConversationHistory history;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(history, "systemPrompt", "SYSTEM_PROMPT");
        ReflectionTestUtils.setField(history, "maxHistory", 2);
    }

    @Test
    void getOrCreate_redisMiss_loadsDbAndWritesCache() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(KEY)).thenReturn(null);
        when(chatMessageMapper.findByBotUser("legacy", "u1")).thenReturn(List.of());

        List<Map<String, Object>> messages = history.getOrCreate("u1");

        assertEquals(1, messages.size()); // 仅 system
        verify(valueOperations).set(eq(KEY), anyString(), eq(TTL));
    }

    @Test
    void getOrCreate_redisHit_skipsDb() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        String cached = "[{\"role\":\"system\",\"content\":\"SYSTEM_PROMPT\"},"
                + "{\"role\":\"user\",\"content\":\"hi\"}]";
        when(valueOperations.get(KEY)).thenReturn(cached);

        List<Map<String, Object>> messages = history.getOrCreate("u1");

        assertEquals(2, messages.size());
        verify(chatMessageMapper, never()).findByBotUser(anyString(), anyString());
    }

    @Test
    void addMessage_writesRedisAndDb() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(KEY)).thenReturn(null);
        when(chatMessageMapper.findByBotUser("legacy", "u1")).thenReturn(List.of());

        history.addMessage("u1", "user", "hello");

        verify(chatMessageMapper).insert(any(ChatMessage.class));
        // getOrCreate 写回一次 + addMessage 追加后写回一次
        verify(valueOperations, org.mockito.Mockito.times(2)).set(eq(KEY), anyString(), eq(TTL));
    }

    @Test
    void redisFailure_fallsBackToDbWithoutThrowing() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(KEY)).thenThrow(new RuntimeException("redis down"));
        when(chatMessageMapper.findByBotUser("legacy", "u1")).thenReturn(List.of());

        List<Map<String, Object>> messages = history.getOrCreate("u1");

        assertEquals(1, messages.size());
    }

    @Test
    void trim_trimsCacheAndDb() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        List<Map<String, Object>> seeded = new ArrayList<>();
        seeded.add(Map.of("role", "system", "content", "SYSTEM_PROMPT"));
        for (int i = 1; i <= 5; i++) {
            seeded.add(Map.of("role", "user", "content", "m" + i));
        }
        when(valueOperations.get(KEY)).thenReturn(OBJECT_MAPPER.writeValueAsString(seeded));

        history.trim("u1");

        verify(chatMessageMapper).trimOldMessages("legacy", "u1", 4);
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(KEY), captor.capture(), eq(TTL));
        List<?> trimmed = OBJECT_MAPPER.readValue(captor.getValue(), List.class);
        // maxHistory=2 → 裁剪到 system + 3 条（现有双删行为，循环每次移除 2 条）
        assertEquals(4, trimmed.size());
    }

    @Test
    void clear_deletesRedisAndDb() {
        history.clear("u1");
        verify(redisTemplate).delete(KEY);
        verify(chatMessageMapper).deleteByBotUser("legacy", "u1");
    }
}
