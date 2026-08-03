package io.github.wangyangxu.ailink.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Metaso（秘塔搜索）API 客户端 —— 联网搜索行业新闻、政策动态。
 */
@Component
public class MetasoClient {

    private static final Logger log = LoggerFactory.getLogger(MetasoClient.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${metaso.api-key}")
    private String apiKey;

    @Value("${metaso.base-url}")
    private String baseUrl;

    public MetasoClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 执行搜索并返回 Top 5 结果摘要。
     * @param query     搜索关键词
     * @param timeRange 时间范围（如 "30d"），可选
     * @return JSON 数组字符串，每条含 title/summary/url/date
     */
    public String search(String query, String timeRange) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("q", query);
            body.put("scope", "webpage");
            body.put("includeSummary", true);
            body.put("size", 5);
            body.put("conciseSnippet", true);
            // timeRange 暂不支持，预留参数

            ResponseEntity<String> resp = restTemplate.postForEntity(
                    baseUrl + "/api/v1/search",
                    new HttpEntity<>(body, headers),
                    String.class);

            JsonNode root = objectMapper.readTree(resp.getBody());
            JsonNode webpages = root.get("webpages");
            if (webpages == null || !webpages.isArray()) {
                return "[]";
            }

            // 精简摘要
            List<Map<String, String>> items = new ArrayList<>();
            int count = 0;
            for (JsonNode item : webpages) {
                if (count >= 5) break;
                Map<String, String> summary = new LinkedHashMap<>();
                summary.put("title", safeText(item, "title"));
                summary.put("snippet", safeText(item, "snippet"));
                summary.put("url", safeText(item, "link"));
                summary.put("date", safeText(item, "date"));
                items.add(summary);
                count++;
            }

            return objectMapper.writeValueAsString(items);

        } catch (Exception e) {
            log.error("Metaso 搜索失败: query={}", query, e);
            return "{\"error\": \"联网搜索失败: " + e.getMessage() + "\"}";
        }
    }

    private static String safeText(JsonNode node, String field) {
        return node.has(field) ? node.get(field).asText() : "";
    }
}
