package io.github.wangyangxu.ailink.service;

import com.github.wechat.ilink.sdk.core.model.FileItem;
import io.github.wangyangxu.ailink.model.KnowledgeDocument;
import io.github.wangyangxu.ailink.rag.KnowledgeIndexService;
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
 * 持久化保存后提取全文交给大模型总结／回答。
 * <p>
 * 流程（v2.6 起接入 RAG）：
 * <pre>
 *   downloadMedia → 持久化保存到 data/documents → Tika 提取全文
 *   → 灌入知识库（标题感知切分 + 向量化，见 KnowledgeIndexService）
 *   → 覆盖率采样（覆盖全文，而不是只取前 2000 字）写入对话历史
 *   → 调文本大模型总结 → 返回结果
 * </pre>
 * 后续针对该文件的追问由 {@code search_knowledge} 工具按相关性检索片段，
 * 不再依赖把全文塞进上下文；文件路径写入历史后，LLM 仍可通过 word_document 工具修改该文件。
 */
@Service
public class ChatFileService {

    private static final Logger log = LoggerFactory.getLogger(ChatFileService.class);

    /**
     * 入库失败时的兜底字符数（保证「知识库不可用」时功能不消失）。
     * 正常路径不再截断：全文入库后按覆盖率采样，长文档也能拿到中段与结尾。
     */
    private static final int FALLBACK_CONTENT_CHARS = 2000;

    /** 入库成功后写进历史的采样字符数（覆盖全文的等距抽样） */
    private static final int COVERAGE_SAMPLE_CHARS = 4000;

    private static final String DEFAULT_DOCUMENT_DIR = "data/documents";

    @Value("${document.output-dir:" + DEFAULT_DOCUMENT_DIR + "}")
    private String outputDir;

    @Autowired
    private IintService iintService;

    @Autowired
    private ConversationHistory history;

    @Autowired
    private ChatTextService chatTextService;

    @Autowired
    private KnowledgeIndexService knowledgeIndexService;

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

            // 3. 用 Apache Tika 提取全文
            File storedFile = storedPath.toFile();
            String extractedText = new Tika().parseToString(storedFile);
            log.info("Tika 提取到 {} 字", extractedText.length());

            // 4. 灌入知识库并取覆盖率采样（失败降级为「前 N 字」，功能不消失）
            IndexInfo info = indexIntoKnowledgeBase(userId, fileName, absoluteFilePath, extractedText);

            // 5. 存入对话历史（包含文件路径 + 知识库标识，让 LLM 知道文件位置与检索入口）
            String historyEntry = "[用户发送了一个文件，文件名：" + fileName
                    + "，服务器路径：" + absoluteFilePath
                    + "，全文 " + extractedText.length() + " 字"
                    + "，已建立知识库索引：" + (info.indexed()
                            ? "是（片段数 " + info.chunkCount() + "，后续可用 search_knowledge 检索该文件细节）"
                            : "否（本次仅提供下方摘要）")
                    + "，内容采样：\n" + info.sample() + "\n]";
            history.addMessage(userId, "user", historyEntry);

            // 6. 调用文本大模型总结
            String reply = chatTextService.chat(userId,
                    "根据上面我发的文件内容，请帮我总结一下核心内容。如果文件内容不足以总结，请直接告诉我。"
                    + "注意：该文件已保存在服务器路径：" + absoluteFilePath
                    + "，如果我后续要求修改此文件，你可以使用 word_document 工具通过该路径进行操作；"
                    + "如果我问文件里的细节，可以调用 search_knowledge 工具检索该文件的相关片段。");

            log.info("文件总结完成 userId={}, fileName={}", userId, fileName);
            return reply;

        } catch (Exception e) {
            log.error("文件处理异常 userId={}, fileName={}", userId, fileName, e);
            return "【错误】文件处理失败，请稍后重试";
        }
    }

    /** 入库结果 + 采样文本 */
    private record IndexInfo(boolean indexed, long documentId, int chunkCount, String sample) {}

    /**
     * 全文灌库：切分 → 向量化 → 落库，然后按覆盖率采样出一段代表性文本。
     * 任何异常都在这里收口，不让入库问题打断文件问答。
     */
    private IndexInfo indexIntoKnowledgeBase(String userId, String fileName, String absoluteFilePath, String text) {
        try {
            KnowledgeIndexService.IndexOutcome outcome = knowledgeIndexService.indexText(
                    KnowledgeDocument.SOURCE_USER_UPLOAD, absoluteFilePath, fileName, text);
            String sample = knowledgeIndexService.coverageSample(outcome.documentId(), COVERAGE_SAMPLE_CHARS);
            if (sample.isBlank()) {
                sample = text.substring(0, Math.min(text.length(), FALLBACK_CONTENT_CHARS));
            }
            log.info("文件已入库 userId={} docId={} 片段={}（向量化 {}）",
                    userId, outcome.documentId(), outcome.chunkCount(), outcome.vectorizedCount());
            return new IndexInfo(true, outcome.documentId(), outcome.chunkCount(), sample);
        } catch (Exception e) {
            log.warn("文件入库失败，降级为前 {} 字摘要: {}", FALLBACK_CONTENT_CHARS, e.getMessage());
            return new IndexInfo(false, -1L, 0,
                    text.substring(0, Math.min(text.length(), FALLBACK_CONTENT_CHARS)));
        }
    }
}