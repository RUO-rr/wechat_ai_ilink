package io.github.wangyangxu.ailink.tool.impl.word;

import io.github.wangyangxu.ailink.service.IintService;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Word 文档操作接口（策略模式）。
 * 每个实现类代表一种文档操作（如创建、添加标题、格式化等），
 * 由 WordDocumentTool 自动收集并按 operation 名称路由分发。
 * <p>
 * 新增操作只需新建实现类并标注 @Component，零侵入已有代码。
 */
public interface WordOperation {

    /** 操作名称，对应 LLM 传入的 operation 字段值，如 "add_heading" */
    String name();

    /** 操作描述，用于生成工具定义中的 enum 说明 */
    String description();

    /** 该操作独有的参数 schema（会合并到工具定义的 properties 中） */
    Map<String, Object> parameters();

    /**
     * 执行操作。
     *
     * @param args        LLM 传入的完整参数 JSON
     * @param outputDir   文档输出目录
     * @param iintService 微信消息发送服务（用于发送文件）
     * @return 执行结果 JSON 字符串
     */
    String execute(JsonNode args, String outputDir, IintService iintService) throws Exception;
}
