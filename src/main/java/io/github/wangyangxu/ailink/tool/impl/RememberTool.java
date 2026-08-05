package io.github.wangyangxu.ailink.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.wangyangxu.ailink.model.AgentMemory;
import io.github.wangyangxu.ailink.service.BotContext;
import io.github.wangyangxu.ailink.service.MemoryService;
import io.github.wangyangxu.ailink.tool.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 长期笔记工具 —— 只读写 memory_type='note'，与自动提取的 fact/preference 逻辑隔离。
 * <ul>
 *   <li>write：写入笔记（dimension 固定 user_note，永不 supersede）</li>
 *   <li>list：列出 active 笔记</li>
 *   <li>delete：软删除笔记（status=deleted，保留审计轨迹）</li>
 * </ul>
 */
@Component
public class RememberTool implements ToolDefinition {

    private static final Logger log = LoggerFactory.getLogger(RememberTool.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String TOOL_NAME = "remember";

    @Autowired
    private MemoryService memoryService;

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String domain() {
        return "general";
    }

    @Override
    public Map<String, Object> getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("operation", Map.of(
                "type", "string",
                "enum", List.of("write", "list", "delete"),
                "description", "操作类型：write=写入笔记，list=列出笔记，delete=删除笔记"
        ));
        properties.put("content", Map.of(
                "type", "string",
                "description", "笔记内容（write 时必填）"
        ));
        properties.put("note_id", Map.of(
                "type", "integer",
                "description", "笔记 ID（delete 时必填，来自 write/list 返回）"
        ));

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of("operation"));

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", TOOL_NAME);
        function.put("description", "管理用户的长期笔记：写入、查看、删除。"
                + "笔记会持久化保存并在后续对话中自动注入，不会被自动覆盖，需要时由用户主动删除。"
                + "当用户说\"记住xxx\"、\"提醒我xxx\"、\"记一下xxx\"时调用。");
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
            String operation = args.path("operation").asText("").trim();
            String userId = BotContext.currentWechatUserId();
            if (userId == null || userId.isBlank()) {
                return "{\"error\": \"缺少用户上下文，无法操作记忆\"}";
            }

            switch (operation) {
                case "write" -> {
                    String content = args.path("content").asText("").trim();
                    if (content.isBlank()) {
                        return "{\"error\": \"content 不能为空\"}";
                    }
                    Long noteId = memoryService.addNote(userId, content);
                    return "{\"success\": true, \"note_id\": " + noteId
                            + ", \"message\": \"已记住，后续对话会自动参考该笔记\"}";
                }
                case "list" -> {
                    List<AgentMemory> notes = memoryService.listNotes(userId);
                    List<Map<String, Object>> list = new ArrayList<>();
                    for (AgentMemory n : notes) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("note_id", n.getId());
                        item.put("content", n.getContent());
                        item.put("created_at", n.getCreatedAt());
                        list.add(item);
                    }
                    return "{\"success\": true, \"notes\": " + objectMapper.writeValueAsString(list) + "}";
                }
                case "delete" -> {
                    long noteId = args.path("note_id").asLong(-1);
                    if (noteId <= 0) {
                        return "{\"error\": \"note_id 无效\"}";
                    }
                    memoryService.deleteNote(userId, noteId);
                    return "{\"success\": true, \"message\": \"笔记已删除\"}";
                }
                default -> {
                    return "{\"error\": \"不支持的操作: " + operation + "，可用操作: write/list/delete\"}";
                }
            }
        } catch (Exception e) {
            log.error("remember 工具执行失败", e);
            return "{\"error\": \"笔记操作异常: " + e.getMessage() + "\"}";
        }
    }
}
