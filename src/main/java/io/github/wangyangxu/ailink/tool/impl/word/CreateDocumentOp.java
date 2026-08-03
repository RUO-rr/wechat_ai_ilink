package io.github.wangyangxu.ailink.tool.impl.word;

import io.github.wangyangxu.ailink.service.IintService;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 创建 Word 文档操作。
 */
@Component
public class CreateDocumentOp implements WordOperation {

    private static final Logger log = LoggerFactory.getLogger(CreateDocumentOp.class);
    private static final int DEFAULT_FONT_SIZE = 12;

    @Override
    public String name() {
        return "create_document";
    }

    @Override
    public String description() {
        return "create_document=创建新Word文档，可指定标题和正文内容";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("title", Map.of(
                "type", "string",
                "description", "文档标题文本"
        ));
        params.put("content", Map.of(
                "type", "string",
                "description", "文档正文内容，支持多行换行（用\\n分隔），仅创建文档时使用"
        ));
        return params;
    }

    @Override
    public String execute(JsonNode args, String outputDir, IintService iintService) throws Exception {
        String title = args.has("title") ? args.get("title").asText() : null;
        String content = args.has("content") ? args.get("content").asText() : null;
        String filePath = args.has("file_path") ? args.get("file_path").asText() : null;

        Path docDir = Paths.get(outputDir);
        Files.createDirectories(docDir);

        if (filePath == null || filePath.isBlank()) {
            String safeTitle = (title != null && !title.isBlank())
                    ? title.replaceAll("[\\\\/:*?\"<>|]", "_")
                    : "document";
            if (safeTitle.length() > 20) safeTitle = safeTitle.substring(0, 20);
            filePath = outputDir + "/" + safeTitle + "_" + System.currentTimeMillis() + ".docx";
        } else {
            filePath = WordOpHelper.resolvePath(filePath, outputDir);
        }

        log.info("创建Word文档: filePath={}, title={}, contentLen={}",
                filePath, title, content != null ? content.length() : 0);

        try (XWPFDocument doc = new XWPFDocument()) {
            if (title != null && !title.isBlank()) {
                XWPFParagraph titlePara = doc.createParagraph();
                titlePara.setStyle("Heading1");
                XWPFRun titleRun = titlePara.createRun();
                titleRun.setText(title);
                titleRun.setBold(true);
                titleRun.setFontSize(18);
                titleRun.setFontFamily("微软雅黑");
            }

            if (content != null && !content.isBlank()) {
                for (String line : content.split("\\n")) {
                    XWPFParagraph para = doc.createParagraph();
                    XWPFRun run = para.createRun();
                    run.setText(line);
                    run.setFontSize(DEFAULT_FONT_SIZE);
                    run.setFontFamily("宋体");
                }
            }

            try (FileOutputStream fos = new FileOutputStream(filePath)) {
                doc.write(fos);
            }
        }

        log.info("Word文档创建成功: {}", filePath);
        return WordOpHelper.buildResult("create_document", filePath, "文档创建成功");
    }
}
