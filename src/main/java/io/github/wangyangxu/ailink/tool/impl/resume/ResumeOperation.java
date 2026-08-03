package io.github.wangyangxu.ailink.tool.impl.resume;

import io.github.wangyangxu.ailink.service.IintService;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 简历操作接口（策略模式）。
 * <p>
 * 每个实现类代表一种简历操作（如生成 Word 简历、生成 HTML 简历、生成 Markdown 简历等），
 * 由 {@link ResumeTool} 自动收集并按 operation 名称路由分发。
 * <p>
 * 参考：resume-builder-cn 的证据驱动简历生成工作流（resources/resume-builder/）。
 *
 * @see ResumeTool
 */
public interface ResumeOperation {

    /** 操作名称，对应 LLM 传入的 operation 字段值，如 "generate_resume" */
    String name();

    /** 操作描述，用于生成工具定义中的 enum 说明 */
    String description();

    /** 该操作独有的参数 schema（会合并到工具定义的 properties 中） */
    Map<String, Object> parameters();

    /**
     * 执行操作。
     *
     * @param args        LLM 传入的完整参数 JSON
     * @param outputDir   简历输出目录
     * @param iintService 微信消息发送服务（用于发送文件）
     * @return 执行结果 JSON 字符串
     */
    String execute(JsonNode args, String outputDir, IintService iintService) throws Exception;
}
