package io.github.wangyangxu.ailink.tool.impl;

import io.github.wangyangxu.ailink.service.WeatherService;
import io.github.wangyangxu.ailink.tool.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 天气查询工具。
 * 实现 ToolDefinition 接口 + @Component，Spring 启动时自动注册到 ToolRegistry。
 */
@Component
public class WeatherTool implements ToolDefinition {

    private static final Logger log = LoggerFactory.getLogger(WeatherTool.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String TOOL_NAME = "get_weather";

    /** 工具定义 JSON Schema（不可变，全局复用） */
    private static final Map<String, Object> DEFINITION = Map.of(
            "type", "function",
            "function", Map.of(
                    "name", TOOL_NAME,
                    "description", "查询指定城市的实时天气，返回天气状况、气温、湿度、风力风向等信息",
                    "parameters", Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "city", Map.of(
                                            "type", "string",
                                            "description", "城市名称，如'北京'、'上海'、'杭州'"
                                    )
                            ),
                            "required", List.of("city")
                    )
            )
    );

    @Autowired
    private WeatherService weatherService;

    @Override
    public String getName() { return TOOL_NAME; }

    @Override
    public String domain() { return "weather"; }

    @Override
    public Map<String, Object> getDefinition() {
        return DEFINITION;
    }

    @Override
    public String execute(String argumentsJson) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            String city = args.get("city").asText();
            if (city == null || city.isBlank()) {
                return "城市名称不能为空";
            }
            log.info("查询天气: city={}", city);
            return weatherService.getWeather(city);
        } catch (Exception e) {
            log.error("解析天气查询参数失败: {}", e.getMessage(), e);
            return "天气查询参数解析失败：" + e.getMessage();
        }
    }
}
