package io.github.wangyangxu.ailink.tool.impl;

import io.github.wangyangxu.ailink.client.MetasoClient;
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
 * 行业新闻搜索工具 —— 联网搜索行业政策、公司动态、发展规划。
 */
@Component
public class SearchIndustryNewsTool implements ToolDefinition {

    private static final Logger log = LoggerFactory.getLogger(SearchIndustryNewsTool.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String TOOL_NAME = "search_industry_news";

    @Autowired
    private MetasoClient metaso;

    @Override
    public String getName() { return TOOL_NAME; }

    @Override
    public String domain() { return "company"; }

    @Override
    public Map<String, Object> getDefinition() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", TOOL_NAME,
                        "description", "联网搜索行业政策、公司动态、发展规划等最新信息。当用户需要了解行业趋势、政策动向、公司最新动态时调用。",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "query", Map.of(
                                                "type", "string",
                                                "description", "搜索关键词，如'新能源汽车 政策 2026'、'小米 造车 进展'"
                                        ),
                                        "time_range", Map.of(
                                                "type", "string",
                                                "description", "时间范围，如'7d'（近7天）、'30d'（近30天），默认30天"
                                        )
                                ),
                                "required", List.of("query")
                        )
                )
        );
    }

    @Override
    public String execute(String argumentsJson) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            String query = args.has("query") ? args.get("query").asText() : null;
            String timeRange = args.has("time_range") ? args.get("time_range").asText() : null;

            if (query == null || query.isBlank()) {
                return "{\"error\": \"请提供搜索关键词（query）\"}";
            }

            log.info("联网搜索: query={}, timeRange={}", query, timeRange);

            String results = metaso.search(query, timeRange);

            return "{" +
                    "\"success\": true, " +
                    "\"query\": \"" + query + "\", " +
                    "\"results\": " + results + ", " +
                    "\"message\": \"搜索完成\"" +
                    "}";

        } catch (Exception e) {
            log.error("搜索失败", e);
            return "{\"error\": \"搜索异常: " + e.getMessage() + "\"}";
        }
    }
}
