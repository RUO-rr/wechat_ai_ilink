package io.github.wangyangxu.ailink.tool.impl.word;

import io.github.wangyangxu.ailink.service.IintService;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 调整标题格式操作。
 * 通过文本匹配找到目标标题段落，修改其字体、字号、加粗、斜体、下划线等格式。
 */
@Component
public class FormatHeadingOp implements WordOperation {

    private static final Logger log = LoggerFactory.getLogger(FormatHeadingOp.class);

    @Override
    public String name() {
        return "format_heading";
    }

    @Override
    public String description() {
        return "format_heading=调整指定标题的格式（字体、字号、加粗、斜体、下划线、颜色）";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("target_text", Map.of(
                "type", "string",
                "description", "要格式化的标题文本（模糊匹配），如'第一章'。若为'all_headings'则格式化所有标题"
        ));
        params.put("font_family", Map.of(
                "type", "string",
                "description", "字体名称，如'微软雅黑'、'宋体'、'黑体'、'楷体'。不传则不修改"
        ));
        params.put("font_size", Map.of(
                "type", "integer",
                "description", "字号（磅），如16、18、22。不传则不修改"
        ));
        params.put("bold", Map.of(
                "type", "boolean",
                "description", "是否加粗。不传则不修改"
        ));
        params.put("italic", Map.of(
                "type", "boolean",
                "description", "是否斜体。不传则不修改"
        ));
        params.put("underline", Map.of(
                "type", "boolean",
                "description", "是否添加下划线。不传则不修改"
        ));
        params.put("color", Map.of(
                "type", "string",
                "description", "字体颜色，十六进制RGB如'FF0000'表示红色、'0000FF'表示蓝色。不传则不修改"
        ));
        return params;
    }

    @Override
    public String execute(JsonNode args, String outputDir, IintService iintService) throws Exception {
        String filePath = args.has("file_path") ? args.get("file_path").asText() : null;
        String targetText = args.has("target_text") ? args.get("target_text").asText() : null;

        if (filePath == null || filePath.isBlank()) {
            return "{\"error\": \"请提供文档路径（file_path）\"}";
        }
        if (targetText == null || targetText.isBlank()) {
            return "{\"error\": \"请提供要格式化的标题文本（target_text）\"}";
        }

        filePath = WordOpHelper.resolvePath(filePath, outputDir);
        String err = WordOpHelper.checkFileExists(filePath);
        if (err != null) return err;

        // 解析格式参数
        String fontFamily = args.has("font_family") ? args.get("font_family").asText() : null;
        Integer fontSize = args.has("font_size") ? args.get("font_size").asInt() : null;
        Boolean bold = args.has("bold") ? args.get("bold").asBoolean() : null;
        Boolean italic = args.has("italic") ? args.get("italic").asBoolean() : null;
        Boolean underline = args.has("underline") ? args.get("underline").asBoolean() : null;
        String color = args.has("color") ? args.get("color").asText() : null;

        log.info("格式化标题: file={}, target={}, font={}, size={}, bold={}, italic={}, underline={}, color={}",
                filePath, targetText, fontFamily, fontSize, bold, italic, underline, color);

        try (FileInputStream fis = new FileInputStream(filePath);
             XWPFDocument doc = new XWPFDocument(fis)) {

            int modified = 0;
            boolean matchAll = "all_headings".equalsIgnoreCase(targetText);

            for (XWPFParagraph para : doc.getParagraphs()) {
                String style = para.getStyle();
                boolean isHeading = style != null && style.startsWith("Heading");

                // 匹配逻辑：all_headings 匹配所有标题段落；否则按文本模糊匹配
                boolean matched;
                if (matchAll) {
                    matched = isHeading;
                } else {
                    String paraText = para.getText();
                    matched = paraText != null && paraText.contains(targetText);
                }

                if (!matched) continue;

                // 对该段落的所有 Run 应用格式
                for (XWPFRun run : para.getRuns()) {
                    applyFormat(run, fontFamily, fontSize, bold, italic, underline, color);
                }

                // 如果段落没有 Run（极少见），创建一个
                if (para.getRuns().isEmpty()) {
                    XWPFRun run = para.createRun();
                    applyFormat(run, fontFamily, fontSize, bold, italic, underline, color);
                }

                modified++;
            }

            if (modified == 0) {
                return "{\"error\": \"未找到匹配的标题: " + targetText + "\"}";
            }

            try (FileOutputStream fos = new FileOutputStream(filePath)) {
                doc.write(fos);
            }

            log.info("标题格式化完成: modified={}", modified);
            return WordOpHelper.buildResult("format_heading", filePath,
                    "已格式化" + modified + "个标题段落");
        }
    }

    /**
     * 对单个 Run 应用格式设置（只修改传入的非 null 属性）。
     */
    private void applyFormat(XWPFRun run, String fontFamily, Integer fontSize,
                             Boolean bold, Boolean italic, Boolean underline, String color) {
        if (fontFamily != null && !fontFamily.isBlank()) {
            run.setFontFamily(fontFamily);
        }
        if (fontSize != null && fontSize > 0) {
            run.setFontSize(fontSize);
        }
        if (bold != null) {
            run.setBold(bold);
        }
        if (italic != null) {
            run.setItalic(italic);
        }
        if (underline != null) {
            run.setUnderline(underline ? UnderlinePatterns.SINGLE : UnderlinePatterns.NONE);
        }
        if (color != null && !color.isBlank()) {
            run.setColor(color);
        }
    }
}
