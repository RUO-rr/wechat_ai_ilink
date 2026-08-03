package io.github.wangyangxu.ailink.tool.impl.word;

import io.github.wangyangxu.ailink.service.BotContext;
import io.github.wangyangxu.ailink.service.IintService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Word 操作公共工具方法：路径解析、文件发送、结果 JSON 构建。
 */
public final class WordOpHelper {

    private static final Logger log = LoggerFactory.getLogger(WordOpHelper.class);

    private WordOpHelper() {}

    /**
     * 解析文件路径：如果是纯文件名则拼接 outputDir 前缀。
     */
    public static String resolvePath(String filePath, String outputDir) {
        if (filePath != null && !filePath.contains("/") && !filePath.contains("\\")) {
            return outputDir + "/" + filePath;
        }
        return filePath;
    }

    /**
     * 校验文件是否存在，不存在返回错误 JSON，存在返回 null。
     */
    public static String checkFileExists(String filePath) {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            return "{\"error\": \"文档不存在: " + filePath + "\"}";
        }
        return null;
    }

    /**
     * 通过 IintService 将文件发送给当前用户（从 UserIdContext 获取 userId）。
     *
     * @return true 表示发送成功
     */
    public static boolean sendFileToUser(String filePath, IintService iintService) {
        String botId = BotContext.currentBotId();
        String userId = BotContext.currentUserId();
        if (botId == null || userId == null) {
            log.debug("BotContext 为空，跳过工具内部发送: {}", filePath);
            return false;
        }
        try {
            Path path = Paths.get(filePath);
            byte[] fileBytes = Files.readAllBytes(path);
            String fileName = path.getFileName().toString();
            iintService.sendFile(botId, userId, fileBytes, fileName, null);
            log.info("工具内部已发送文件: botId={}, userId={}, file={}", botId, userId, fileName);
            return true;
        } catch (Exception e) {
            log.error("工具内部发送文件失败: {}", filePath, e);
            return false;
        }
    }

    /**
     * 构造统一的成功结果 JSON。
     */
    public static String buildResult(String operation, String filePath, String message) {
        return buildResult(operation, filePath, message, false);
    }

    /**
     * 构造统一的成功结果 JSON，sent=true 时标记文件已被工具内部发送。
     */
    public static String buildResult(String operation, String filePath, String message, boolean sent) {
        return "{" +
                "\"success\": true, " +
                "\"operation\": \"" + operation + "\", " +
                "\"file_path\": \"" + filePath + "\", " +
                (sent ? "\"sent\": true, " : "") +
                "\"message\": \"" + message + "\"" +
                "}";
    }
}
