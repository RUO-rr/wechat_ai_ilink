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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在 Word 文档指定位置插入图片操作。
 * 支持末尾、开头、关键词段落前后四种插入位置，
 * 图片默认自动缩放至最大 500px 宽并保持比例。
 */
@Component
public class AddImageOp implements WordOperation {

    private static final Logger log = LoggerFactory.getLogger(AddImageOp.class);

    /** 自动缩放时的最大宽度（像素） */
    private static final int DEFAULT_MAX_WIDTH_PX = 500;
    /** EMU per pixel at 96 DPI */
    private static final int EMU_PER_PIXEL = 9525;

    @Override
    public String name() {
        return "add_image";
    }

    @Override
    public String description() {
        return "add_image=在文档指定位置插入图片，支持末尾/开头/关键词前后插入，自动缩放至500px宽";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("image_path", Map.of(
                "type", "string",
                "description", "图片文件路径，从 generate_image 工具返回的 local_path 中获取"
        ));
        params.put("position", Map.of(
                "type", "string",
                "description", "插入位置：'end'=文档末尾（默认），'beginning'=文档开头，"
                        + "'after_text'=在匹配段落后插入，'before_text'=在匹配段落前插入"
        ));
        params.put("position_text", Map.of(
                "type", "string",
                "description", "当 position 为 after_text 或 before_text 时，用于匹配段落的关键词"
        ));
        params.put("width", Map.of(
                "type", "integer",
                "description", "图片显示宽度（像素），不传则自动缩放至最大500px宽并保持比例"
        ));
        params.put("height", Map.of(
                "type", "integer",
                "description", "图片显示高度（像素），不传则根据宽度自动计算"
        ));
        return params;
    }

    @Override
    public String execute(JsonNode args, String outputDir, IintService iintService) throws Exception {
        String filePath = args.has("file_path") ? args.get("file_path").asText() : null;
        String imagePath = args.has("image_path") ? args.get("image_path").asText() : null;
        String position = args.has("position") ? args.get("position").asText() : "end";
        String positionText = args.has("position_text") ? args.get("position_text").asText() : null;
        Integer reqWidth = args.has("width") ? args.get("width").asInt() : null;
        Integer reqHeight = args.has("height") ? args.get("height").asInt() : null;

        // 参数校验
        if (filePath == null || filePath.isBlank()) {
            return "{\"error\": \"请提供文档路径（file_path）\"}";
        }
        if (imagePath == null || imagePath.isBlank()) {
            return "{\"error\": \"请提供图片路径（image_path），可从 generate_image 返回的 local_path 获取\"}";
        }
        if (!List.of("end", "beginning", "after_text", "before_text").contains(position)) {
            return "{\"error\": \"position 必须是 end/beginning/after_text/before_text 之一\"}";
        }
        if (("after_text".equals(position) || "before_text".equals(position))
                && (positionText == null || positionText.isBlank())) {
            return "{\"error\": \"使用 after_text/before_text 位置时必须提供 position_text\"}";
        }

        // 解析并校验文档路径
        filePath = WordOpHelper.resolvePath(filePath, outputDir);
        String err = WordOpHelper.checkFileExists(filePath);
        if (err != null) return err;

        // 解析图片路径：纯文件名尝试 data/images/ 前缀
        String resolvedImagePath = imagePath;
        if (!imagePath.contains("/") && !imagePath.contains("\\")) {
            String candidate = "data/images/" + imagePath;
            if (Files.exists(Paths.get(candidate))) {
                resolvedImagePath = candidate;
            }
        }
        if (!Files.exists(Paths.get(resolvedImagePath))) {
            return "{\"error\": \"图片文件不存在: " + resolvedImagePath + "\"}";
        }

        // 读取图片字节
        byte[] imageBytes = Files.readAllBytes(Paths.get(resolvedImagePath));

        // 检测图片格式
        String lowerPath = resolvedImagePath.toLowerCase();
        int pictureType;
        String imageFilename;
        if (lowerPath.endsWith(".png")) {
            pictureType = XWPFDocument.PICTURE_TYPE_PNG;
            imageFilename = "image.png";
        } else if (lowerPath.endsWith(".jpg") || lowerPath.endsWith(".jpeg")) {
            pictureType = XWPFDocument.PICTURE_TYPE_JPEG;
            imageFilename = "image.jpg";
        } else if (lowerPath.endsWith(".gif")) {
            pictureType = XWPFDocument.PICTURE_TYPE_GIF;
            imageFilename = "image.gif";
        } else if (lowerPath.endsWith(".bmp")) {
            pictureType = XWPFDocument.PICTURE_TYPE_BMP;
            imageFilename = "image.bmp";
        } else {
            return "{\"error\": \"不支持的图片格式: " + lowerPath + "（支持 png/jpg/gif/bmp）\"}";
        }

        // 读取图片原始尺寸
        BufferedImage bufImg = ImageIO.read(new java.io.File(resolvedImagePath));
        if (bufImg == null) {
            return "{\"error\": \"无法读取图片尺寸，文件可能已损坏: " + resolvedImagePath + "\"}";
        }
        int origWidthPx = bufImg.getWidth();
        int origHeightPx = bufImg.getHeight();

        // 计算显示尺寸（EMU = px × 9525）
        int widthPx, heightPx;
        if (reqWidth != null && reqHeight != null) {
            widthPx = Math.max(reqWidth, 1);
            heightPx = Math.max(reqHeight, 1);
        } else if (reqWidth != null) {
            widthPx = Math.max(reqWidth, 1);
            heightPx = Math.round((float) origHeightPx / origWidthPx * widthPx);
        } else if (reqHeight != null) {
            heightPx = Math.max(reqHeight, 1);
            widthPx = Math.round((float) origWidthPx / origHeightPx * heightPx);
        } else {
            // 自动缩放：最大宽度 500px，等比缩放，原图小于 500 不放大
            if (origWidthPx <= DEFAULT_MAX_WIDTH_PX) {
                widthPx = origWidthPx;
                heightPx = origHeightPx;
            } else {
                double ratio = (double) DEFAULT_MAX_WIDTH_PX / origWidthPx;
                widthPx = DEFAULT_MAX_WIDTH_PX;
                heightPx = (int) Math.round(origHeightPx * ratio);
            }
        }
        int widthEMU = widthPx * EMU_PER_PIXEL;
        int heightEMU = heightPx * EMU_PER_PIXEL;

        log.info("插入图片: file={}, image={}, origSize={}x{}px, displaySize={}x{}px, position={}, posText={}",
                filePath, resolvedImagePath, origWidthPx, origHeightPx, widthPx, heightPx, position, positionText);

        // 打开文档，插入图片
        try (FileInputStream fis = new FileInputStream(filePath);
             XWPFDocument doc = new XWPFDocument(fis)) {

            XWPFParagraph imagePara;

            switch (position) {
                case "beginning":
                    if (doc.getParagraphs().isEmpty()) {
                        imagePara = doc.createParagraph();
                    } else {
                        XmlCursor cursor = doc.getParagraphs().get(0).getCTP().newCursor();
                        imagePara = doc.insertNewParagraph(cursor);
                        cursor.dispose();
                    }
                    break;

                case "before_text":
                    int beforeIdx = findParagraphIndex(doc, positionText);
                    if (beforeIdx >= 0) {
                        XmlCursor cursor = doc.getParagraphs().get(beforeIdx).getCTP().newCursor();
                        imagePara = doc.insertNewParagraph(cursor);
                        cursor.dispose();
                    } else {
                        log.warn("未找到匹配段落 '{}'，图片将添加到文档末尾", positionText);
                        imagePara = doc.createParagraph();
                    }
                    break;

                case "after_text":
                    int afterIdx = findParagraphIndex(doc, positionText);
                    if (afterIdx >= 0 && afterIdx < doc.getParagraphs().size() - 1) {
                        XmlCursor cursor = doc.getParagraphs().get(afterIdx + 1).getCTP().newCursor();
                        imagePara = doc.insertNewParagraph(cursor);
                        cursor.dispose();
                    } else if (afterIdx >= 0) {
                        imagePara = doc.createParagraph();
                    } else {
                        log.warn("未找到匹配段落 '{}'，图片将添加到文档末尾", positionText);
                        imagePara = doc.createParagraph();
                    }
                    break;

                case "end":
                default:
                    imagePara = doc.createParagraph();
                    break;
            }

            XWPFRun run = imagePara.createRun();
            try (ByteArrayInputStream bais = new ByteArrayInputStream(imageBytes)) {
                run.addPicture(bais, pictureType, imageFilename, widthEMU, heightEMU);
            }

            try (FileOutputStream fos = new FileOutputStream(filePath)) {
                doc.write(fos);
            }

            log.info("图片插入成功: file={}, image={}", filePath, resolvedImagePath);

            String posDesc;
            switch (position) {
                case "beginning": posDesc = "文档开头"; break;
                case "before_text": posDesc = "\"" + positionText + "\"段落之前"; break;
                case "after_text": posDesc = "\"" + positionText + "\"段落之后"; break;
                default: posDesc = "文档末尾";
            }

            return WordOpHelper.buildResult("add_image", filePath,
                    "图片已插入" + posDesc + "（尺寸: " + widthPx + "x" + heightPx + "px）");
        }
    }

    /** 模糊查找包含关键词的段落索引。 */
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