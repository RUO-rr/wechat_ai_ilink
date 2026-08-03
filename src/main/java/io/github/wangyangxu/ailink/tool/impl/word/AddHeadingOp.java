package io.github.wangyangxu.ailink.tool.impl.word;

import io.github.wangyangxu.ailink.service.IintService;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.xmlbeans.XmlCursor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 添加多级标题操作。
 * 支持在文档末尾或指定段落后插入 1-6 级标题，并可自定义标题格式。
 */
@Component
public class AddHeadingOp implements WordOperation {

    private static final Logger log = LoggerFactory.getLogger(AddHeadingOp.class);

    /** 各级标题默认字号 */
    private static final int[] DEFAULT_HEADING_SIZES = {22, 18, 16, 14, 13, 12};

    @Override
    public String name() {
        return "add_heading";
    }

    @Override
    public String description() {
        return "add_heading=在文档中添加多级标题（1-6级），可指定插入位置和格式";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("heading_level", Map.of(
                "type", "integer",
                "description", "标题级别，1-6（1为最高级），默认1"
        ));
        params.put("heading_text", Map.of(
                "type", "string",
                "description", "标题文本内容"
        ));
        params.put("position", Map.of(
                "type", "string",
                "description", "插入位置：'end'=文档末尾（默认），或输入要插入在其后的段落文本关键词（模糊匹配）"
        ));
        return params;
    }

    @Override
    public String execute(JsonNode args, String outputDir, IintService iintService) throws Exception {
        String filePath = args.has("file_path") ? args.get("file_path").asText() : null;
        String headingText = args.has("heading_text") ? args.get("heading_text").asText() : null;
        int level = args.has("heading_level") ? args.get("heading_level").asInt() : 1;
        String position = args.has("position") ? args.get("position").asText() : "end";

        if (filePath == null || filePath.isBlank()) {
            return "{\"error\": \"请提供文档路径（file_path）\"}";
        }
        if (headingText == null || headingText.isBlank()) {
            return "{\"error\": \"请提供标题文本（heading_text）\"}";
        }
        if (level < 1 || level > 6) {
            return "{\"error\": \"标题级别应在1-6之间\"}";
        }

        filePath = WordOpHelper.resolvePath(filePath, outputDir);
        String err = WordOpHelper.checkFileExists(filePath);
        if (err != null) return err;

        log.info("添加标题: file={}, level={}, text={}, position={}", filePath, level, headingText, position);

        try (FileInputStream fis = new FileInputStream(filePath);
             XWPFDocument doc = new XWPFDocument(fis)) {

            XWPFParagraph headingPara;

            if ("end".equalsIgnoreCase(position) || position == null || position.isBlank()) {
                // 在文档末尾添加
                headingPara = doc.createParagraph();
            } else {
                // 查找匹配关键词的段落，在其后插入
                int insertIndex = findParagraphIndex(doc, position);
                if (insertIndex >= 0 && insertIndex < doc.getParagraphs().size() - 1) {
                    XmlCursor cursor = doc.getParagraphs().get(insertIndex + 1).getCTP().newCursor();
                    headingPara = doc.insertNewParagraph(cursor);
                    cursor.dispose();
                } else if (insertIndex >= 0) {
                    // 匹配到最后一段，在末尾追加
                    headingPara = doc.createParagraph();
                } else {
                    // 未找到匹配，在末尾追加
                    log.warn("未找到匹配段落 '{}'，标题将添加到文档末尾", position);
                    headingPara = doc.createParagraph();
                }
            }

            // 设置标题样式
            headingPara.setStyle("Heading" + level);

            // 创建 Run 并设置格式
            XWPFRun run = headingPara.createRun();
            run.setText(headingText);
            run.setBold(true);
            run.setFontSize(DEFAULT_HEADING_SIZES[level - 1]);
            run.setFontFamily("微软雅黑");

            try (FileOutputStream fos = new FileOutputStream(filePath)) {
                doc.write(fos);
            }

            log.info("标题添加成功: level={}, text={}", level, headingText);
            return WordOpHelper.buildResult("add_heading", filePath,
                    "已添加" + level + "级标题: " + headingText);
        }
    }

    /**
     * 模糊查找包含关键词的段落索引。
     */
    private int findParagraphIndex(XWPFDocument doc, String keyword) {
        for (int i = 0; i < doc.getParagraphs().size(); i++) {
            String text = doc.getParagraphs().get(i).getText();
            if (text != null && text.contains(keyword)) {
                return i;
            }
        }
        return -1;
    }
}
