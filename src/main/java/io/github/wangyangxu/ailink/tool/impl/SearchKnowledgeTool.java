package io.github.wangyangxu.ailink.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.wangyangxu.ailink.rag.KnowledgeRetriever;
import io.github.wangyangxu.ailink.rag.RetrievalResult;
import io.github.wangyangxu.ailink.tool.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 知识库检索工具 —— 让模型自己决定「什么时候需要翻资料」。
 * <p>
 * 定位：内置知识资产（简历方法论 / 模板 / 案例 / 评价标准）与用户上传文档都进了同一个知识库，
 * 模型通过本工具按需检索，而不是把这些资料全文塞进 system prompt（那是另一种烧 token 的方式）。
 * 领域标记为 {@code general}，任何路由分支都会带上它。
 */
@Component
public class SearchKnowledgeTool implements ToolDefinition {

    private static final Logger log = LoggerFactory.getLogger(SearchKnowledgeTool.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String TOOL_NAME = "search_knowledge";

    private static final int MAX_TOP_K = 10;

    @Autowired
    private KnowledgeRetriever knowledgeRetriever;

    @Override
    public String getName() { return TOOL_NAME; }

    @Override
    public String domain() { return "general"; }

    @Override
    public Map<String, Object> getDefinition() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", TOOL_NAME,
                        "description", "检索本地知识库（内置简历方法论/模板/案例，以及用户上传过的文档）。"
                                + "当用户的问题涉及文档细节、写作规范、模板选择、评价标准，或需要引用历史资料时调用。"
                                + "应该用具体的检索语句调用（例如「学生简历如何写项目经历」），而不是把用户原话原样搬过来。",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "query", Map.of(
                                                "type", "string",
                                                "description", "检索语句：要查的具体问题或关键词组合"
                                        ),
                                        "top_k", Map.of(
                                                "type", "integer",
                                                "description", "返回片段数，默认 4，最大 10"
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
            JsonNode queryNode = args.get("query");
            if (queryNode == null || queryNode.asText().isBlank()) {
                return "检索失败：query 不能为空。";
            }
            String query = queryNode.asText().trim();
            int topK = args.hasNonNull("top_k") ? args.get("top_k").asInt(4) : 4;
            if (topK <= 0 || topK > MAX_TOP_K) {
                topK = Math.max(1, Math.min(MAX_TOP_K, topK <= 0 ? 4 : topK));
            }
            log.info("知识库检索: query='{}', topK={}", query, topK);
            RetrievalResult result = knowledgeRetriever.retrieve(query, topK);
            if (result.isEmpty()) {
                return "知识库中没有检索到与「" + query + "」相关的内容。"
                        + "请基于已知信息回答，或告知用户知识库暂无该资料，不要编造引用。";
            }
            return result.contextText();
        } catch (Exception e) {
            log.error("知识库检索失败: {}", e.getMessage(), e);
            return "知识库检索失败：" + e.getMessage();
        }
    }
}