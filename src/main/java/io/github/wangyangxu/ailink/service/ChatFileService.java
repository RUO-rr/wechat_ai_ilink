package io.github.wangyangxu.ailink.service;

import com.github.wechat.ilink.sdk.core.model.FileItem;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件识别服务：接收用户发送的 Word/PDF/Excel/TXT 文件，
 * 持久化保存后提取文本内容交给大模型总结／回答。
 * <p>
 * 流程：
 * <pre>
 *   downloadMedia → 持久化保存到 data/documents → Tika 提取文字 → 截取前 2000 字
 *   → 存入对话历史（含文件路径） → 调文本大模型总结 → 返回结果
 * </pre>
 * 文件路径写入历史后，LLM 可通过 Function Calling 调用 WordDocumentTool 修改该文件。
 */
@Service
public class ChatFileService {

    private static final Logger log = LoggerFactory.getLogger(ChatFileService.class);

    /** 提取文件文本时最大喂给大模型的字符数 */
    private static final int MAX_CONTENT_CHARS = 2000;

    private static final String DEFAULT_DOCUMENT_DIR = "data/documents";

    @Value("${document.output-dir:" + DEFAULT_DOCUMENT_DIR + "}")
    private String outputDir;

    @Autowired
    private IintService iintService;

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

        try {
            // 1. 从微信 CDN 下载文件
            byte[] fileBytes = iintService.downloadMedia(BotContext.currentBotId(), fileItem.getMedia());
            if (fileBytes == null || fileBytes.length == 0) {
                log.error("下载文件为空: {}", fileName);
                return "【错误】文件下载失败，请重试。";
            }
            log.info("文件下载成功: {}, 大小: {} bytes", fileName, fileBytes.length);

            // 2. 持久化保存到 data/documents 目录（不再用临时文件，后续工具可直接操作）
            Path docDir = Paths.get(outputDir);
            Files.createDirectories(docDir);
            String safeName = fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
            String storedFileName = System.currentTimeMillis() + "_" + safeName;
            Path storedPath = docDir.resolve(storedFileName);
            Files.write(storedPath, fileBytes);
            String absoluteFilePath = storedPath.toAbsolutePath().toString();
            log.info("文件已持久化保存: {}", absoluteFilePath);

            // 3. 用 Apache Tika 提取文本内容
            File storedFile = storedPath.toFile();
            Tika tika = new Tika();
            String extractedText = tika.parseToString(storedFile);
            log.info("Tika 提取到 {} 字", extractedText.length());

            // 4. 截取前 MAX_CONTENT_CHARS 字，防止爆 Token
            String contentSummary = extractedText.substring(0,
                    Math.min(extractedText.length(), MAX_CONTENT_CHARS));

            // 5. 存入对话历史（包含文件路径，让 LLM 知道文件位置以便后续修改）
            String historyEntry = "[用户发送了一个文件，文件名：" + fileName
                    + "，服务器路径：" + absoluteFilePath
                    + "，内容摘要：\n" + contentSummary + "\n]";
            history.addMessage(userId, "user", historyEntry);

            // 6. 调用文本大模型总结
            String reply = chatTextService.chat(userId,
                    "根据上面我发的文件内容，请帮我总结一下核心内容。如果文件内容不足以总结，请直接告诉我。"
                    + "注意：该文件已保存在服务器路径：" + absoluteFilePath
                    + "，如果我后续要求修改此文件，你可以使用 word_document 工具通过该路径进行操作。");

            log.info("文件总结完成 userId={}, fileName={}", userId, fileName);
            return reply;

        } catch (Exception e) {
            log.error("文件处理异常 userId={}, fileName={}", userId, fileName, e);
            return "【错误】文件处理失败，请稍后重试";
        }
    }
}