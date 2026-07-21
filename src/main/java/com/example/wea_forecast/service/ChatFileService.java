package com.example.wea_forecast.service;

import com.github.wechat.ilink.sdk.core.model.FileItem;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;

/**
 * 文件识别服务：接收用户发送的 Word/PDF/Excel/TXT 文件，
 * 提取文本内容后交给大模型总结／回答。
 * <p>
 * 流程：
 * <pre>
 *   downloadMedia → 存临时文件 → Tika 提取文字 → 截取前 2000 字
 *   → 存入对话历史 → 调文本大模型总结 → 返回结果
 * </pre>
 */
@Service
public class ChatFileService {

    private static final Logger log = LoggerFactory.getLogger(ChatFileService.class);

    /** 提取文件文本时最大喂给大模型的字符数 */
    private static final int MAX_CONTENT_CHARS = 2000;

    @Autowired
    private IlinkService ilinkService;

    @Autowired
    private ConversationHistory history;

    @Autowired
    private ChatTextService chatTextService;

    /**
     * 处理用户发送的文件消息。
     *
     * @param userId   微信用户 ID
     * @param fileItem 文件消息对象（包含 media、file_name 等）
     * @return 大模型对文件内容的总结或回答
     */
    public String chat(String userId, FileItem fileItem) {
        String fileName = fileItem.getFile_name();
        log.info("收到文件 userId={}, fileName={}", userId, fileName);

        File tempFile = null;
        try {
            // 1. 从微信 CDN 下载文件
            byte[] fileBytes = ilinkService.downloadMedia(fileItem.getMedia());
            if (fileBytes == null || fileBytes.length == 0) {
                log.error("下载文件为空: {}", fileName);
                return "【错误】文件下载失败，请重试。";
            }
            log.info("文件下载成功: {}, 大小: {} bytes", fileName, fileBytes.length);

            // 2. 保存到临时文件
            tempFile = Files.createTempFile("chat_file_", "_" + fileName).toFile();
            Files.write(tempFile.toPath(), fileBytes);
            log.info("临时文件已保存: {}", tempFile.getAbsolutePath());

            // 3. 用 Apache Tika 提取文本内容
            Tika tika = new Tika();
            String extractedText = tika.parseToString(tempFile);
            log.info("Tika 提取到 {} 字", extractedText.length());

            // 4. 截取前 MAX_CONTENT_CHARS 字，防止爆 Token
            String contentSummary = extractedText.substring(0,
                    Math.min(extractedText.length(), MAX_CONTENT_CHARS));

            // 5. 存入对话历史
            String historyEntry = "[用户发送了一个文件，文件名：" + fileName + "，内容摘要：\n" + contentSummary + "\n]";
            history.addMessage(userId, "user", historyEntry);

            // 6. 调用文本大模型总结
            String reply = chatTextService.chat(userId,
                    "根据上面我发的文件内容，请帮我总结一下核心内容。如果文件内容不足以总结，请直接告诉我。");

            log.info("文件总结完成 userId={}, fileName={}", userId, fileName);
            return reply;

        } catch (Exception e) {
            log.error("文件处理异常 userId={}, fileName={}: {}", userId, fileName, e.getMessage(), e);
            return "【错误】文件处理失败：" + e.getMessage();
        } finally {
            // 7. 清理临时文件
            if (tempFile != null && tempFile.exists()) {
                boolean deleted = tempFile.delete();
                if (!deleted) {
                    log.warn("临时文件删除失败，将在 JVM 退出时自动清理: {}", tempFile.getAbsolutePath());
                    tempFile.deleteOnExit();
                }
            }
        }
    }
}