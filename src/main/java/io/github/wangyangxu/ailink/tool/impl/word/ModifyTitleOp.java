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
 * 修改文档标题操作（修改第一个 Heading 段落的文本）。
 */
@Component
public class ModifyTitleOp implements WordOperation {

    private static final Logger log = LoggerFactory.getLogger(ModifyTitleOp.class);

    @Override
    public String name() {
        return "modify_title";
    }

    @Override
    public String description() {
        return "modify_title=修改文档的第一个标题文本";
    }

    @Override
    public Map<String, Object> parameters() {
        // title 参数已在 CreateDocumentOp 中定义，此处无需重复
        return new LinkedHashMap<>();
    }

    @Override
    public String execute(JsonNode args, String outputDir, IintService iintService) throws Exception {
        String filePath = args.has("file_path") ? args.get("file_path").asText() : null;
        String newTitle = args.has("title") ? args.get("title").asText() : null;

        if (filePath == null || filePath.isBlank()) {
            return "{\"error\": \"请提供要修改的文档路径（file_path）\"}";
        }
        if (newTitle == null || newTitle.isBlank()) {
            return "{\"error\": \"请提供新的标题文本（title）\"}";
        }

        filePath = WordOpHelper.resolvePath(filePath, outputDir);
        String err = WordOpHelper.checkFileExists(filePath);
        if (err != null) return err;

        log.info("修改Word文档标题: {} -> {}", filePath, newTitle);

        try (FileInputStream fis = new FileInputStream(filePath);
             XWPFDocument doc = new XWPFDocument(fis)) {

            boolean found = false;
            for (XWPFParagraph para : doc.getParagraphs()) {
                String style = para.getStyle();
                if (style != null && style.startsWith("Heading")) {
                    for (int i = para.getRuns().size() - 1; i >= 0; i--) {
                        para.removeRun(i);
                    }
                    XWPFRun run = para.createRun();
                    run.setText(newTitle);
                    run.setBold(true);
                    run.setFontSize(18);
                    run.setFontFamily("微软雅黑");
                    found = true;
                    break;
                }
            }

            if (!found) {
                XWPFParagraph newTitlePara;
                if (!doc.getParagraphs().isEmpty()) {
                    XmlCursor cursor = doc.getParagraphs().get(0).getCTP().newCursor();
                    newTitlePara = doc.insertNewParagraph(cursor);
                    cursor.dispose();
                } else {
                    newTitlePara = doc.createParagraph();
                }
                newTitlePara.setStyle("Heading1");
                XWPFRun run = newTitlePara.createRun();
                run.setText(newTitle);
                run.setBold(true);
                run.setFontSize(18);
                run.setFontFamily("微软雅黑");
            }

            try (FileOutputStream fos = new FileOutputStream(filePath)) {
                doc.write(fos);
            }
        }

        log.info("Word文档标题修改成功: {}", filePath);
        return WordOpHelper.buildResult("modify_title", filePath, "标题已修改为: " + newTitle);
    }
}
