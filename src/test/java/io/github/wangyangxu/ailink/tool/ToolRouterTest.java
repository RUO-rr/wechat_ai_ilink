package io.github.wangyangxu.ailink.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolRouterTest {

    @Mock
    private ToolRegistry toolRegistry;

    private ToolDefinition tool(String name, String domain) {
        return new ToolDefinition() {
            @Override
            public String getName() { return name; }
            @Override
            public String domain() { return domain; }
            @Override
            public Map<String, Object> getDefinition() {
                return Map.of("function", Map.of("name", name));
            }
            @Override
            public String execute(String argumentsJson) { return ""; }
        };
    }

    private ToolRouter router() {
        return new ToolRouter(toolRegistry, new ToolRouter.FallbackPromptBuilder(toolRegistry));
    }

    @Test
    void singleDomainSignal_filtersToDomainPlusGeneral() {
        ToolDefinition weather = tool("get_weather", "weather");
        ToolDefinition company = tool("search_company_info", "company");
        ToolDefinition remember = tool("remember", "general");
        when(toolRegistry.getAllTools()).thenReturn(List.of(weather, company, remember));

        ToolRouter.RouteResult route = router().route("北京天气怎么样");

        assertFalse(route.isFallback());
        assertEquals("weather", route.matchedDomain());
        assertEquals(2, route.tools().size());
        assertTrue(route.tools().stream().anyMatch(d -> "get_weather".equals(
                ((Map<?, ?>) d.get("function")).get("name"))));
        assertTrue(route.tools().stream().anyMatch(d -> "remember".equals(
                ((Map<?, ?>) d.get("function")).get("name"))));
        assertFalse(route.tools().stream().anyMatch(d -> "search_company_info".equals(
                ((Map<?, ?>) d.get("function")).get("name"))));
    }

    @Test
    void noSignal_fallsBackToAllTools() {
        ToolDefinition weather = tool("get_weather", "weather");
        when(toolRegistry.getAllTools()).thenReturn(List.of(weather));
        when(toolRegistry.getAllDefinitions()).thenReturn(List.of(weather.getDefinition()));

        ToolRouter.RouteResult route = router().route("随便聊聊");

        assertTrue(route.isFallback());
        assertEquals(1, route.tools().size());
    }

    @Test
    void multiDomainSignals_fallsBack() {
        ToolDefinition weather = tool("get_weather", "weather");
        ToolDefinition company = tool("search_company_info", "company");
        when(toolRegistry.getAllTools()).thenReturn(List.of(weather, company));
        when(toolRegistry.getAllDefinitions()).thenReturn(List.of(
                weather.getDefinition(), company.getDefinition()));

        ToolRouter.RouteResult route = router().route("查小米公司的天气");

        assertTrue(route.isFallback());
        assertEquals(2, route.tools().size());
    }
}
