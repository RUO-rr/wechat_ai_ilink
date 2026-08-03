package io.github.wangyangxu.ailink.tool.impl;

import io.github.wangyangxu.ailink.service.IintService;
import io.github.wangyangxu.ailink.tool.ToolDefinition;
import io.github.wangyangxu.ailink.tool.impl.word.WordOperation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Word 文档操作工具 —— Function Calling 瘦壳路由。
 * <p>
 * 对外暴露一个 "word_document" 工具名，内部按 operation 字段分发到各 WordOperation 实现。
 * 新增操作只需新建 WordOperation 实现类并标注 @Component，本类零修改。
 */
@Component
public class WordDocumentTool implements ToolDefinition {

    private static final Logger log = LoggerFactory.getLogger(WordDocumentTool.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String TOOL_NAME = "word_document";
    private static final String DOCUMENT_DIR = "data/documents";

    @Value("${document.output-dir:" + DOCUMENT_DIR + "}")
    private String outputDir;

    private final IintService iintService;

    /** 操作名 → 操作实例 的路由表 */
    private final Map<String, WordOperation> operations;

    /**
     * Spring 自动注入所有 WordOperation 实现类，构建路由表。
     */
    public WordDocumentTool(List<WordOperation> ops, IintService iintService) {
        Map<String, WordOperation> map = new LinkedHashMap<>();
        for (WordOperation op : ops) {
            map.put(op.name(), op);
        }
        this.operations = Collections.unmodifiableMap(map);
        this.iintService = iintService;
        log.info("WordDocumentTool 已注册 {} 个操作: {}", operations.size(),
                operations.keySet().stream().collect(Collectors.joining(", ")));
    }

    @Override
    public String getName() { return TOOL_NAME; }

    @Override
    public String domain() { return "document"; }

    @Override
    public Map<String, Object> getDefinition() {
        // 动态聚合所有操作的 enum 和 description
        List<String> opNames = new ArrayList<>(operations.keySet());
        String opDescription = operations.values().stream()
                .map(WordOperation::description)
                .collect(Collectors.joining("，"));

        // 动态聚合所有操作的参数（去重合并）
        Map<String, Object> properties = new LinkedHashMap<>();

        // 公共参数：operation 和 file_path
        properties.put("operation", Map.of(
                "type", "string",
                "enum", opNames,
                "description", "操作类型：" + opDescription
        ));
        properties.put("file_path", Map.of(
                "type", "string",
                "description", "文档文件路径。除 create_document 外其他操作必填；可从之前工具调用的返回结果中获取"
        ));

        // 各操作独有参数
        for (WordOperation op : operations.values()) {
            Map<String, Object> opParams = op.parameters();
            if (opParams != null) {
                for (Map.Entry<String, Object> entry : opParams.entrySet()) {
                    properties.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
        }

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of("operation"));

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", TOOL_NAME);
        function.put("description", "操作Word文档，支持创建文档、添加多级标题、修改标题、调整标题格式（字体/加粗/斜体/下划线/颜色）、"
                + "设置首行缩进、发送文件给用户。"
                + "当用户需要创建或编辑Word文档时调用此工具。"
                + "file_path 可从之前工具调用的返回结果中获取。");
        function.put("parameters", parameters);

        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("type", "function");
        definition.put("function", function);
        return definition;
    }

    @Override
    public String execute(String argumentsJson) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            String operation = args.has("operation") ? args.get("operation").asText() : "";

            if (operation.isBlank()) {
                String available = String.join("、", operations.keySet());
                return "{\"error\": \"请指定操作类型（operation）：" + available + "\"}";
            }

            WordOperation op = operations.get(operation.trim());
            if (op == null) {
                return "{\"error\": \"不支持的操作: " + operation + "，可用操作: "
                        + String.join("、", operations.keySet()) + "\"}";
            }

            return op.execute(args, outputDir, iintService);

        } catch (Exception e) {
            log.error("Word文档工具执行失败", e);
            return "{\"error\": \"Word文档操作异常: " + e.getMessage() + "\"}";
        }
    }
}
