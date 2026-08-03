package io.github.wangyangxu.ailink.tool;

import java.util.Map;

/**
 * Function Calling 工具定义接口。
 * 每个工具实现此接口并标注 @Component，即可被 ToolRegistry 自动发现和注册。
 * 新增工具只需新建实现类，零侵入已有代码。
 */
public interface ToolDefinition {

    /** 工具名称，对应 DeepSeek/OpenAI function.name */
    String getName();

    /**
     * 工具所属领域，用于动态装配时按领域筛选工具子集。
     * 默认 "general" 表示通用工具，始终注入。
     */
    default String domain() {
        return "general";
    }

    /** 完整的工具定义 JSON Schema（含 type 和 function 外层） */
    Map<String, Object> getDefinition();

    /**
     * 执行工具逻辑。
     * @param argumentsJson 模型传回的 arguments JSON 字符串，如 {"city":"北京"}
     * @return 工具执行结果，作为 tool 消息的 content 回传模型
     */
    String execute(String argumentsJson);
}
