package io.github.wangyangxu.ailink.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工具注册中心。Spring 启动时自动收集所有 ToolDefinition 实现类，
 * 对外提供统一的工具定义列表和执行路由。
 * <p>
 * ChatTextService 只依赖此注册中心，不感知具体工具实现。
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, ToolDefinition> tools;

    /**
     * Spring 自动注入所有 ToolDefinition Bean。
     * 用 LinkedHashMap 保证注册顺序稳定。
     */
    public ToolRegistry(List<ToolDefinition> toolDefinitions) {
        Map<String, ToolDefinition> map = new LinkedHashMap<>();
        for (ToolDefinition tool : toolDefinitions) {
            map.put(tool.getName(), tool);
        }
        this.tools = Collections.unmodifiableMap(map);
        log.info("ToolRegistry 已注册 {} 个工具: {}", tools.size(),
                tools.keySet().stream().collect(Collectors.joining(", ")));
    }

    /**
     * 返回所有已注册工具的完整定义列表，追加到 LLM 请求体的 tools 字段。
     */
    public List<Map<String, Object>> getAllDefinitions() {
        return tools.values().stream()
                .map(ToolDefinition::getDefinition)
                .collect(Collectors.toList());
    }

    /** 返回所有已注册的 ToolDefinition 实例（用于领域路由等元信息访问） */
    public List<ToolDefinition> getAllTools() {
        return List.copyOf(tools.values());
    }

    /**
     * 按工具名称执行。
     * @param name          工具名称（模型返回的 function.name）
     * @param argumentsJson 模型返回的 function.arguments JSON 字符串
     * @return 工具执行结果
     */
    public String execute(String name, String argumentsJson) {
        ToolDefinition tool = tools.get(name);
        if (tool == null) {
            log.warn("未知工具调用: {}", name);
            return "未知工具：" + name;
        }
        log.debug("执行工具: name={}, args={}", name, argumentsJson);
        try {
            return tool.execute(argumentsJson);
        } catch (Exception e) {
            log.error("工具 {} 执行失败", name, e);
            return "工具执行失败：" + e.getMessage();
        }
    }
}
