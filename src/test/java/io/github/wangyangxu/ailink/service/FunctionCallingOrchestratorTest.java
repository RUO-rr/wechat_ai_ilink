package io.github.wangyangxu.ailink.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.wangyangxu.ailink.client.LlmClient;
import io.github.wangyangxu.ailink.tool.ToolRegistry;
import io.github.wangyangxu.ailink.tool.ToolRouter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FunctionCallingOrchestratorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mock
    private LlmClient llmClient;

    @Mock
    private ToolRegistry toolRegistry;

    @Mock
    private ToolRouter toolRouter;

    @Mock
    private MetricsService metricsService;

    @InjectMocks
    private FunctionCallingOrchestrator orchestrator;

    private static ToolRouter.RouteResult plainRoute() {
        return new ToolRouter.RouteResult(List.of(), false, null, null);
    }

    private static List<Map<String, Object>> baseMessages() {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", "你是助手"));
        messages.add(Map.of("role", "user", "content", "你好"));
        return messages;
    }

    @Test
    void singleRoundPlainResponse_returnsContent() throws Exception {
        JsonNode plain = OBJECT_MAPPER.readTree(
                "{\"choices\":[{\"message\":{\"content\":\"你好，有什么可以帮你\",\"tool_calls\":null}}]}");
        when(llmClient.callChatApi(any(), any(), any())).thenReturn(plain);
        when(toolRouter.route(anyString())).thenReturn(plainRoute());

        FunctionCallingOrchestrator.Result result =
                orchestrator.execute(baseMessages(), "bot1", "u1");

        assertEquals("你好，有什么可以帮你", result.assistantContent());
        verify(llmClient, times(1)).callChatApi(any(), any(), any());
    }

    @Test
    void toolCallRound_thenPlain_finalAnswerUsesToolResult() throws Exception {
        JsonNode toolResp = OBJECT_MAPPER.readTree(
                "{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":["
                        + "{\"id\":\"call_1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"北京\\\"}\"}}]}}]}");
        JsonNode plainResp = OBJECT_MAPPER.readTree(
                "{\"choices\":[{\"message\":{\"content\":\"北京今天晴\",\"tool_calls\":null}}]}");
        when(llmClient.callChatApi(any(), any(), any())).thenReturn(toolResp, plainResp);
        when(toolRouter.route(anyString())).thenReturn(plainRoute());
        when(toolRegistry.execute("get_weather", "{\"city\":\"北京\"}"))
                .thenReturn("{\"success\": true, \"weather\": \"晴\"}");

        FunctionCallingOrchestrator.Result result =
                orchestrator.execute(baseMessages(), "bot1", "u1");

        assertEquals("北京今天晴", result.assistantContent());
        verify(toolRegistry).execute("get_weather", "{\"city\":\"北京\"}");
        verify(llmClient, times(2)).callChatApi(any(), any(), any());
    }

    @Test
    void repeatedSameToolCall_triggerLoopProtection() throws Exception {
        JsonNode toolResp = OBJECT_MAPPER.readTree(
                "{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":["
                        + "{\"id\":\"call_1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"北京\\\"}\"}}]}}]}");
        when(llmClient.callChatApi(any(), any(), any())).thenReturn(toolResp, toolResp);
        when(toolRouter.route(anyString())).thenReturn(plainRoute());
        when(toolRegistry.execute(anyString(), anyString()))
                .thenReturn("{\"success\": true, \"weather\": \"晴\"}");

        FunctionCallingOrchestrator.Result result =
                orchestrator.execute(baseMessages(), "bot1", "u1");

        assertEquals("任务已完成。", result.assistantContent());
        verify(toolRegistry, times(2)).execute(anyString(), anyString());
    }

    @Test
    void maxStepsReached_terminatesWithMessage() throws Exception {
        JsonNode toolResp = OBJECT_MAPPER.readTree(
                "{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":["
                        + "{\"id\":\"call_1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"北京\\\"}\"}}]}}]}");
        when(llmClient.callChatApi(any(), any(), any())).thenAnswer(inv -> toolResp);
        when(toolRouter.route(anyString())).thenReturn(plainRoute());
        AtomicInteger counter = new AtomicInteger();
        when(toolRegistry.execute(anyString(), anyString())).thenAnswer(inv -> {
            int i = counter.incrementAndGet();
            // 交替成功/失败，避免触发循环保护，确保走到最大步数
            return i % 2 == 0
                    ? "{\"success\": false, \"error\": \"x\"}"
                    : "{\"success\": true, \"weather\": \"晴\"}";
        });

        FunctionCallingOrchestrator.Result result =
                orchestrator.execute(baseMessages(), "bot1", "u1");

        assertEquals("任务执行步骤过多，已自动终止，请简化需求后重试。", result.assistantContent());
        verify(llmClient, times(15)).callChatApi(any(), any(), any());
    }

    @Test
    void orphanToolMessage_removedBeforeLoop() throws Exception {
        JsonNode plain = OBJECT_MAPPER.readTree(
                "{\"choices\":[{\"message\":{\"content\":\"好的\",\"tool_calls\":null}}]}");
        when(llmClient.callChatApi(any(), any(), any())).thenReturn(plain);
        when(toolRouter.route(anyString())).thenReturn(plainRoute());

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", "你是助手"));
        messages.add(Map.of("role", "tool", "tool_call_id", "call_1", "content", "孤儿tool消息"));
        messages.add(Map.of("role", "user", "content", "你好"));

        orchestrator.execute(messages, "bot1", "u1");

        // 孤儿 tool 消息应在循环前被清洗，避免 400
        assertEquals("user", messages.get(1).get("role"));
    }

    @Test
    void llmCallFailure_returnsNetworkErrorMessage() throws Exception {
        when(llmClient.callChatApi(any(), any(), any())).thenThrow(new RuntimeException("timeout"));
        when(toolRouter.route(anyString())).thenReturn(plainRoute());

        FunctionCallingOrchestrator.Result result =
                orchestrator.execute(baseMessages(), "bot1", "u1");

        assertEquals("【网络错误】调用大模型失败：timeout", result.assistantContent());
    }
}
