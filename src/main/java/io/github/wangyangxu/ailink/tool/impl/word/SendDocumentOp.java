package io.github.wangyangxu.ailink.tool.impl.word;

import io.github.wangyangxu.ailink.service.IintService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 发送文档给用户操作。
 */
@Component
public class SendDocumentOp implements WordOperation {

    private static final Logger log = LoggerFactory.getLogger(SendDocumentOp.class);

    @Override
    public String name() {
        return "send_document";
    }

    @Override
    public String description() {
        return "send_document=将文档发送给用户";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("caption", Map.of(
                "type", "string",
                "description", "文件发送时的说明文字"
        ));
        return params;
    }

    @Override
    public String execute(JsonNode args, String outputDir, IintService iintService) throws Exception {
        String filePath = args.has("file_path") ? args.get("file_path").asText() : null;
        String caption = args.has("caption") ? args.get("caption").asText() : null;

        if (filePath == null || filePath.isBlank()) {
            return "{\"error\": \"请提供要发送的文档路径（file_path），可从之前工具调用的返回结果中获取\"}";
        }

        filePath = WordOpHelper.resolvePath(filePath, outputDir);
        String err = WordOpHelper.checkFileExists(filePath);
        if (err != null) return err;

        log.info("发送文档: filePath={}, caption={}", filePath, caption);

        boolean sent = WordOpHelper.sendFileToUser(filePath, iintService);

        return WordOpHelper.buildResult("send_document", filePath,
                "文档已发送" + (caption != null ? "（" + caption + "）" : ""), sent);
    }
}
