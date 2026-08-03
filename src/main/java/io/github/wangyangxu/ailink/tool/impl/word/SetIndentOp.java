package io.github.wangyangxu.ailink.tool.impl.word;

import io.github.wangyangxu.ailink.service.IintService;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设置首行缩进操作。
 */
@Component
public class  SetIndentOp implements WordOperation {

    private static final Logger log = LoggerFactory.getLogger(SetIndentOp.class);
    private static final int TWIPS_PER_CHAR = 240;
    private static final int DEFAULT_INDENT_CHARS = 2;

    @Override
    public String name() {
        return "set_first_line_indent";
    }

    @Override
    public String description() {
        return "set_first_line_indent=设置正文段落的首行缩进";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("indent_chars", Map.of(
                "type", "integer",
                "description", "首行缩进字符数，默认2，范围0-10"
        ));
        return params;
    }

    @Override
    public String execute(JsonNode args, String outputDir, IintService iintService) throws Exception {
        String filePath = args.has("file_path") ? args.get("file_path").asText() : null;
        int indentChars = args.has("indent_chars") ? args.get("indent_chars").asInt() : DEFAULT_INDENT_CHARS;

        if (filePath == null || filePath.isBlank()) {
            return "{\"error\": \"请提供文档路径（file_path）\"}";
        }
        if (indentChars < 0 || indentChars > 10) {
            return "{\"error\": \"缩进字符数应在0-10之间\"}";
        }

        filePath = WordOpHelper.resolvePath(filePath, outputDir);
        String err = WordOpHelper.checkFileExists(filePath);
        if (err != null) return err;

        int indentTwips = indentChars * TWIPS_PER_CHAR;
        log.info("设置首行缩进: {} indentChars={}", filePath, indentChars);

        try (FileInputStream fis = new FileInputStream(filePath);
             XWPFDocument doc = new XWPFDocument(fis)) {

            int modified = 0;
            for (XWPFParagraph para : doc.getParagraphs()) {
                String style = para.getStyle();
                if (style != null && style.startsWith("Heading")) continue;
                String text = para.getText();
                if (text == null || text.isBlank()) continue;
                para.setIndentationFirstLine(indentTwips);
                modified++;
            }

            try (FileOutputStream fos = new FileOutputStream(filePath)) {
                doc.write(fos);
            }

            log.info("首行缩进设置完成: {} modified={}", filePath, modified);
            return "{" +
                    "\"success\": true, " +
                    "\"operation\": \"set_first_line_indent\", " +
                    "\"file_path\": \"" + filePath + "\", " +
                    "\"indent_chars\": " + indentChars + ", " +
                    "\"modified_paragraphs\": " + modified + ", " +
                    "\"message\": \"已设置" + indentChars + "字符首行缩进，共修改" + modified + "个段落\"" +
                    "}";
        }
    }
}
