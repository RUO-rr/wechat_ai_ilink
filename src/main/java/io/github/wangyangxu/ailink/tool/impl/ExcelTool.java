package io.github.wangyangxu.ailink.tool.impl;

import io.github.wangyangxu.ailink.service.BotContext;
import io.github.wangyangxu.ailink.service.IintService;
import io.github.wangyangxu.ailink.tool.ToolDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;

/**
 * Excel 表格工具 —— Function Calling 实现。
 * 支持创建含标题行的 .xlsx 表格、追加数据到已有表格。
 * 自动发送文件给当前对话用户。
 */
@Component
public class ExcelTool implements ToolDefinition {

    private static final Logger log = LoggerFactory.getLogger(ExcelTool.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String DEFAULT_DOCUMENT_DIR = "data/documents";

    private final IintService iintService;
    private final String documentDir;

    public ExcelTool(IintService iintService,
                     @Value("${document.output-dir:" + DEFAULT_DOCUMENT_DIR + "}") String documentDir) {
        this.iintService = iintService;
        this.documentDir = documentDir;
    }

    @Override
    public String getName() {
        return "create_excel";
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
                "description", "已有 .xlsx 文件路径（追加数据时用），不传则新建"
        ));
        properties.put("sheet_name", Map.of(
                "type", "string",
                "description", "Sheet 名称，默认 Sheet1"
        ));
        properties.put("headers", Map.of(
                "type", "string",
                "description", "列标题 JSON 数组，如 [\"姓名\",\"年龄\",\"城市\"]"
        ));
        properties.put("data_rows", Map.of(
                "type", "string",
                "description", "数据行 JSON 二维数组，每行对应一条记录，如 [[\"张三\",28,\"北京\"],[\"李四\",35,\"上海\"]]"
        ));
        properties.put("append", Map.of(
                "type", "boolean",
                "description", "是否追加到已有文件，默认 false。true 时必须传 file_path"
        ));

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", getName());
        function.put("description", "创建或修改 Excel 表格（.xlsx）。支持新建含标题行的表格、追加数据到已有表格。当用户需要将查询结果整理成结构化表格时调用此工具。");
        function.put("parameters", parameters);

        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("type", "function");
        definition.put("function", function);
        return definition;
    }

    @Override
    public String execute(String argumentsJson) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);

            String filePath = args.has("file_path") ? args.get("file_path").asText(): null;
            String sheetName = args.has("sheet_name") ? args.get("sheet_name").asText(): "Sheet1";
            boolean append = args.has("append") && args.get("append").asBoolean();

            // 解析 headers
            List<String> headers = null;
            if (args.has("headers") && !args.get("headers").isNull()) {
                try {
                    headers = objectMapper.readValue(
                            args.get("headers").asText(),
                            objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                } catch (JsonProcessingException e) {
                    return "{\"error\": \"headers 格式错误，需要 JSON 数组，如 [\\\"姓名\\\",\\\"年龄\\\"]\"}";
                }
            }

            // 解析 data_rows
            List<List<Object>> dataRows = null;
            if (args.has("data_rows") && !args.get("data_rows").isNull()) {
                try {
                    dataRows = objectMapper.readValue(
                            args.get("data_rows").asText(),
                            objectMapper.getTypeFactory().constructCollectionType(
                                    List.class,
                                    objectMapper.getTypeFactory().constructCollectionType(List.class, Object.class)));
                } catch (JsonProcessingException e) {
                    return "{\"error\": \"data_rows 格式错误，需要 JSON 二维数组\"}";
                }
            }

            if ((headers == null || headers.isEmpty()) && (dataRows == null || dataRows.isEmpty())) {
                return "{\"error\": \"headers 和 data_rows 不能同时为空\"}";
            }

            if (append && (filePath == null || filePath.isBlank())) {
                return "{\"error\": \"追加模式（append=true）时必须提供 file_path\"}";
            }

            // 创建目录
            Files.createDirectories(Paths.get(documentDir));

            String resolvedPath;
            Workbook workbook;
            Sheet sheet;

            if (append) {
                Path existing = Paths.get(filePath);
                if (!Files.exists(existing)) {
                    return "{\"error\": \"文件不存在: " + escapeJson(filePath) + "\"}";
                }
                if (!filePath.toLowerCase().endsWith(".xlsx")) {
                    return "{\"error\": \"仅支持 .xlsx 格式文件的追加操作\"}";
                }
                try (FileInputStream fis = new FileInputStream(filePath)) {
                    workbook = new XSSFWorkbook(fis);
                }
                sheet = workbook.getSheet(sheetName);
                if (sheet == null) {
                    sheet = workbook.createSheet(sheetName);
                }
                resolvedPath = filePath;
            } else {
                String safeName = sheetName.replaceAll("[\\\\/:*?\"<>|]", "_");
                if (safeName.length() > 20) safeName = safeName.substring(0, 20);
                resolvedPath = documentDir.replace("\\", "/") + "/" + safeName + "_" + Instant.now().toEpochMilli() + ".xlsx";

                workbook = new XSSFWorkbook();
                sheet = workbook.createSheet(sheetName);
            }

            int currentRow = sheet.getLastRowNum();

            // 写入表头
            if (headers != null && !headers.isEmpty()) {
                // 追加模式下，只有 sheet 为空时才写入表头
                if (!append || sheet.getLastRowNum() == 0 || sheet.getRow(0) == null) {
                    currentRow = 0;
                    Row headerRow = sheet.createRow(currentRow);
                    CellStyle headerStyle = createHeaderStyle(workbook);
                    for (int i = 0; i < headers.size(); i++) {
                        Cell cell = headerRow.createCell(i);
                        cell.setCellValue(headers.get(i) != null ? headers.get(i) : "");
                        cell.setCellStyle(headerStyle);
                    }
                    currentRow = 1;
                } else {
                    // 已有表头，从下一行开始追加
                    currentRow++;
                }
            } else if (append) {
                currentRow++;
            }

            // 写入数据行
            if (dataRows != null && !dataRows.isEmpty()) {
                for (List<Object> rowData : dataRows) {
                    if (rowData == null) continue;
                    Row row = sheet.createRow(currentRow++);
                    for (int i = 0; i < rowData.size(); i++) {
                        Cell cell = row.createCell(i);
                        Object val = rowData.get(i);
                        if (val == null) {
                            cell.setCellValue("");
                        } else if (val instanceof Number num) {
                            cell.setCellValue(num.doubleValue());
                        } else {
                            cell.setCellValue(val.toString());
                        }
                    }
                }
            }

            // 保存
            String normalizedPath;
            try (FileOutputStream fos = new FileOutputStream(resolvedPath)) {
                workbook.write(fos);
            }
            workbook.close();
            normalizedPath = resolvedPath.replace("\\", "/");

            int colCount = headers != null ? headers.size()
                    : (dataRows != null && !dataRows.isEmpty() && dataRows.get(0) != null ? dataRows.get(0).size() : 0);
            int rowCount = dataRows != null ? dataRows.size() : 0;
            String message = "Excel已生成（" + colCount + "列" + rowCount + "行）";

            log.info("Excel操作成功: file={}, append={}, cols={}, rows={}", normalizedPath, append, colCount, rowCount);

            // 自动发送文件给当前用户
            boolean sent = sendFileToUser(normalizedPath);

            return "{" +
                    "\"success\": true, " +
                    "\"operation\": \"create_excel\", " +
                    "\"file_path\": \"" + escapeJson(normalizedPath) + "\"" +
                    ", \"sent\": " + sent +
                    ", \"message\": \"" + escapeJson(message) + "\"" +
                    "}";

        } catch (Exception e) {
            log.error("Excel 操作失败", e);
            return "{\"error\": \"Excel操作失败: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    /** 创建表头样式：加粗 + 灰色背景 + 边框 */
    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    /** 通过 IintService + BotContext 发送文件给当前用户 */
    private boolean sendFileToUser(String filePath) {
        String botId = BotContext.currentBotId();
        String userId = BotContext.currentWechatUserId();
        if (botId == null || userId == null) {
            log.debug("BotContext 为空（非对话上下文调用），跳过文件发送: {}", filePath);
            return false;
        }
        try {
            Path path = Paths.get(filePath);
            byte[] fileBytes = Files.readAllBytes(path);
            String fileName = path.getFileName().toString();
            iintService.sendFile(botId, userId, fileBytes, fileName, null);
            log.info("Excel 已发送: botId={}, userId={}, file={}", botId, userId, fileName);
            return true;
        } catch (Exception e) {
            log.error("发送 Excel 文件失败: {}", filePath, e);
            return false;
        }
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
