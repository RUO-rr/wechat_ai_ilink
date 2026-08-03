package io.github.wangyangxu.ailink.tool.impl;

import io.github.wangyangxu.ailink.client.TianyanchaClient;
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
 * 企业工商信息查询工具 —— 聚合天眼查搜索+基本信息+股东+人员+投资。
 * 一个公司名入参 → 完整工商档案出参。
 */
@Component
public class   SearchCompanyInfoTool implements ToolDefinition {

    private static final Logger log = LoggerFactory.getLogger(SearchCompanyInfoTool.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String TOOL_NAME = "search_company_info";

    @Autowired
    private TianyanchaClient tianyancha;

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
                        "description", "根据公司名称查询企业的完整工商档案，包括法人、注册资本、成立时间、经营范围、股东、主要人员、对外投资。当用户需要了解某家公司基本信息时调用。",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "company_name", Map.of(
                                                "type", "string",
                                                "description", "公司全称，如'小米科技有限责任公司'、'北京字节跳动科技有限公司'"
                                        )
                                ),
                                "required", List.of("company_name")
                        )
                )
        );
    }

    @Override
    public String execute(String argumentsJson) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            String companyName = args.has("company_name") ? args.get("company_name").asText() : null;

            if (companyName == null || companyName.isBlank()) {
                return "{\"error\": \"请提供公司名称（company_name）\"}";
            }

            log.info("查询企业信息: companyName={}", companyName);

            // 1. 搜索获取 gid
            String gid = tianyancha.searchCompanyId(companyName);
            if (gid == null) {
                return "{\"error\": \"未找到公司「" + companyName + "」的相关信息\"}";
            }

            // 2. 并行获取全面档案
            String profile = tianyancha.getFullCompanyProfile(gid);

            // 3. 包装为统一结果
            return "{" +
                    "\"success\": true, " +
                    "\"company_name\": \"" + companyName + "\", " +
                    "\"gid\": \"" + gid + "\", " +
                    "\"profile\": " + profile + ", " +
                    "\"message\": \"查询成功\"" +
                    "}";

        } catch (Exception e) {
            log.error("企业信息查询失败", e);
            return "{\"error\": \"企业信息查询异常: " + e.getMessage() + "\"}";
        }
    }
}
