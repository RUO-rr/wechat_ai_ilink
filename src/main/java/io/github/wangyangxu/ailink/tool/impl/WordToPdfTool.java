package io.github.wangyangxu.ailink.tool.impl;

import io.github.wangyangxu.ailink.service.IintService;
import io.github.wangyangxu.ailink.tool.ToolDefinition;
import io.github.wangyangxu.ailink.tool.impl.word.WordOpHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Word 转 PDF 工具 —— 通过 LibreOffice 命令行实现。
 * <p>
 * 支持两种输入来源（文件路径均从对话上下文 / 工具返回结果中获取）：
 * 1. word_document 工具生成的 Word 文档
 * 2. 用户通过微信发送的 Word 文件（ChatFileService 已保存到磁盘）
 * <p>
 * 转换成功后自动将 PDF 发送给用户，结果标记 "sent": true，
 * 避免 Orchestrator 重复发送。
 * <p>
 * 依赖：系统需安装 LibreOffice，可通过 libreoffice.path 配置可执行文件路径。
 */
@Component
public class WordToPdfTool implements ToolDefinition {

    private static final Logger log = LoggerFactory.getLogger(WordToPdfTool.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String TOOL_NAME = "word_to_pdf";

    /** LibreOffice 可执行文件路径（默认在 PATH 中查找 soffice） */
    @Value("${libreoffice.path:soffice}")
    private String libreofficePath;

    private final IintService iintService;

    public WordToPdfTool(IintService iintService) {
        this.iintService = iintService;
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String domain() {
        return "document";
    }

    @Override
    public Map<String, Object> getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("file_path", Map.of(
                "type", "string",
                "description", "Word 文件的本地路径。可从之前工具调用的返回结果（如 word_document 生成的 file_path）"
                        + "或用户上传文件的服务器路径中获取"
        ));

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of("file_path"));

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", TOOL_NAME);
        function.put("description", "将Word文档(.doc/.docx)转换为PDF并自动发送给用户。"
                + "适用于：1) 将 word_document 工具生成的 Word 文件转为 PDF；"
                + "2) 用户上传的 Word 文件转为 PDF。转换完成后会直接发送给用户。");
        function.put("parameters", parameters);

        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("type", "function");
        definition.put("function", function);
        return definition;
    }

    @Override
    public String execute(String argumentsJson) {
        try {
            String clean = argumentsJson.replace("\\\"", "\"");
            JsonNode args = objectMapper.readTree(clean);
            String filePath = args.has("file_path") ? args.get("file_path").asText().trim() : "";

            if (filePath.isBlank()) {
                return "{\"error\": \"请提供 Word 文件路径（file_path）\"}";
            }

            // 纯文件名时拼接输出目录
            filePath = WordOpHelper.resolvePath(filePath, "data/documents");

            Path inPath = Paths.get(filePath).toAbsolutePath().normalize();
            File inFile = inPath.toFile();

            if (!inFile.exists()) {
                return "{\"error\": \"文件不存在: " + filePath + "\"}";
            }
            if (!inFile.isFile()) {
                return "{\"error\": \"路径不是文件: " + filePath + "\"}";
            }

            String name = inFile.getName().toLowerCase();
            if (!name.endsWith(".doc") && !name.endsWith(".docx")) {
                return "{\"error\": \"不支持的文件格式，请提供 .doc 或 .docx 文件，实际: " + inFile.getName() + "\"}";
            }

            // 输出路径：同目录、同名、.pdf 后缀
            String pdfFileName = inFile.getName().replaceAll("(?i)\\.docx?$", "") + ".pdf";
            Path outPath = inPath.getParent().resolve(pdfFileName);
            String outDir = outPath.getParent().toAbsolutePath().toString();

            log.info("Word转PDF: in={}, out={}, soffice={}", inPath, outPath, libreofficePath);

            // 执行 LibreOffice 转换
            int exitCode;
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        libreofficePath,
                        "--headless",
                        "--convert-to", "pdf",
                        "--outdir", outDir,
                        inPath.toAbsolutePath().toString()
                );
                pb.redirectErrorStream(true);
                Process process = pb.start();
                String procOutput = new String(process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                exitCode = process.waitFor();
                log.info("LibreOffice 输出: {}", procOutput.trim());
            } catch (IOException e) {
                String osName = System.getProperty("os.name").toLowerCase();
                String installHint;
                if (osName.contains("win")) {
                    installHint = "请安装 LibreOffice (https://www.libreoffice.org/download/)，"
                            + "或将 libreoffice.path 配置为 soffice.exe 的完整路径";
                } else {
                    installHint = "请安装 LibreOffice: sudo apt install libreoffice / brew install --cask libreoffice";
                }
                return "{\"error\": \"LibreOffice 未找到或无法启动 (" + e.getMessage() + ")。" + installHint + "\"}";
            }

            if (exitCode != 0) {
                return "{\"error\": \"Word转PDF失败，LibreOffice 退出码: " + exitCode + "\"}";
            }

            // 确认输出文件已生成
            if (!outPath.toFile().exists()) {
                return "{\"error\": \"转换完成后未找到输出文件: " + outPath + "\"}";
            }

            long pdfSize = Files.size(outPath);
            log.info("Word转PDF成功: pdfPath={}, size={}字节", outPath, pdfSize);

            // 自动发送 PDF 给用户
            boolean sent = WordOpHelper.sendFileToUser(outPath.toString(), iintService);
            if (sent) {
                log.info("PDF已自动发送给用户: {}", pdfFileName);
            }

            return "{\"success\": true, "
                    + "\"sent\": " + sent + ", "
                    + "\"file_name\": \"" + escapeJson(pdfFileName) + "\", "
                    + "\"size\": " + pdfSize + ", "
                    + "\"message\": \"✅ 转换完成！" + (sent ? "PDF 已发送。" : "PDF 已生成。") + "（" + formatSize(pdfSize) + "）\"}";

        } catch (Exception e) {
            log.error("Word转PDF异常", e);
            return "{\"error\": \"Word转PDF异常: " + e.getMessage() + "\"}";
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
