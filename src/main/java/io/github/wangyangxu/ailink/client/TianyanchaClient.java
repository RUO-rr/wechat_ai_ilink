package io.github.wangyangxu.ailink.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.CompletableFuture;

/**
 * 天眼查 API 客户端 —— 封装企业工商信息查询。
 * 支持搜索企业、基本信息、股东、主要人员、对外投资。
 * 使用 CompletableFuture 并行调用，减少响应延迟。
 */
@Component
public class TianyanchaClient {

    private static final Logger log = LoggerFactory.getLogger(TianyanchaClient.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${tianyancha.api-key}")
    private String apiKey;

    @Value("${tianyancha.base-url}")
    private String baseUrl;

    public TianyanchaClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** 搜索企业 → 返回首个匹配的 gid（企业唯一ID） */
    public String searchCompanyId(String companyName) {
        try {
            String url = baseUrl + "/v3/search?keyword=" + companyName;
            String resp = callWithAuth(url);
            JsonNode root = objectMapper.readTree(resp);
            // 天眼查搜索返回 items 数组
            JsonNode items = root.get("result").get("items");
            if (items != null && items.isArray() && items.size() > 0) {
                return items.get(0).get("id").asText();
            }
        } catch (Exception e) {
            log.error("天眼查搜索失败: companyName={}", companyName, e);
        }
        return null;
    }

    /** 企业基本信息 */
    public String getBaseInfo(String gid) {
        return callApi("/open/business/baseinfo?gid=" + gid, "基本信息", gid);
    }

    /** 股东信息 */
    public String getShareholders(String gid) {
        return callApi("/open/business/holders?gid=" + gid, "股东信息", gid);
    }

    /** 主要人员 */
    public String getKeyPersonnel(String gid) {
        return callApi("/open/business/humans?gid=" + gid, "主要人员", gid);
    }

    /** 对外投资 */
    public String getInvestments(String gid) {
        return callApi("/open/business/invests?gid=" + gid, "对外投资", gid);
    }

    /**
     * 并行获取企业全面信息：基本信息 + 股东 + 主要人员 + 对外投资。
     * @return 合并后的 JSON 字符串
     */
    public String getFullCompanyProfile(String gid) {
        CompletableFuture<String> baseInfo = CompletableFuture.supplyAsync(() -> getBaseInfo(gid));
        CompletableFuture<String> shareholders = CompletableFuture.supplyAsync(() -> getShareholders(gid));
        CompletableFuture<String> personnel = CompletableFuture.supplyAsync(() -> getKeyPersonnel(gid));
        CompletableFuture<String> investments = CompletableFuture.supplyAsync(() -> getInvestments(gid));

        CompletableFuture.allOf(baseInfo, shareholders, personnel, investments).join();

        return "{"
                + "\"base_info\": " + nullToJson(baseInfo.join()) + ","
                + "\"shareholders\": " + nullToJson(shareholders.join()) + ","
                + "\"key_personnel\": " + nullToJson(personnel.join()) + ","
                + "\"investments\": " + nullToJson(investments.join())
                + "}";
    }

    // ======================== 内部方法 ========================

    private String callApi(String path, String label, String gid) {
        try {
            return callWithAuth(baseUrl + path);
        } catch (Exception e) {
            log.warn("天眼查{}获取失败: gid={}", label, gid, e);
            return null;
        }
    }

    private String callWithAuth(String url) {
        String fullUrl = url + (url.contains("?") ? "&" : "?") + "token=" + apiKey;
        return restTemplate.getForObject(fullUrl, String.class);
    }

    private static String nullToJson(String s) {
        return s == null ? "null" : s;
    }
}
