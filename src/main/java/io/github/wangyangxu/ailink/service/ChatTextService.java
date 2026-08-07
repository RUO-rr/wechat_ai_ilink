package io.github.wangyangxu.ailink.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 文本对话门面 —— 协调意图检测、FC 编排、语音文本生成、文件发送。
 * 不包含具体业务逻辑，只做流程编排和依赖组装。
 */
@Service
public class ChatTextService {

    private static final Logger log = LoggerFactory.getLogger(ChatTextService.class);

    @Autowired private ConversationHistory history;
    @Autowired private IntentDetectionService intentService;
    @Autowired private SpeechTextGenerationService speechService;
    @Autowired private FunctionCallingOrchestrator fcOrchestrator;
    @Autowired private IintService iintService;
    @Autowired private MemoryService memoryService;
    @Autowired private MemoryOrchestrator memoryOrchestrator;

    // ======================== 意图检测 ========================

    public String detectIntent(String userId, String userMessage) {
        return intentService.detect(userId, userMessage);
    }

    // ======================== 语音文本生成 ========================

    public String generateSpeechText(String userId, String userMessage) {
        return speechService.generate(userId, userMessage);
    }

    // ======================== 记录助手回复 ========================

    public void recordAssistantReply(String userId, String assistantText) {
        history.getOrCreate(userId);
        history.addMessage(userId, "assistant", assistantText);
        history.trim(userId);
    }

    // ======================== 主聊天流程 ========================

    public String chat(String userId, String userMessage) {
        history.getOrCreate(userId);
        history.addMessage(userId, "user", userMessage);
        return doChat(userId);
    }

    /** 兜底：用户消息已被失败的服务写入历史（如画图），避免重复入史 */
    public String chatFallback(String userId) {
        history.getOrCreate(userId);
        return doChat(userId);
    }

    private String doChat(String userId) {
        // 1.1 注入长期记忆（摘要槽 / 记忆槽 / 笔记槽）
        List<Map<String, Object>> messages = buildMessagesWithMemory(userId);

        // 2. 委托 FC 编排引擎执行
        FunctionCallingOrchestrator.Result result = fcOrchestrator.execute(messages, BotContext.currentBotId(), userId);

        // 3. 发送生成的文件
        sendFiles(userId, result.generatedFiles(), result.explicitlySentFiles());

        // 4. 将 FC 循环中的 tool 消息存入历史（跨轮上下文持久化）
        persistToolMessages(userId, messages);

        // 5. 存入助手回复
        String assistantContent = result.assistantContent();
        // 意图协议标记（[VOICE] / [VOICE_SWITCH:xx]）不入历史，由 MainController 路由
        if (!isIntentMarker(assistantContent)) {
            history.addMessage(userId, "assistant", assistantContent);
            history.trim(userId);
        }

        // 6. 记忆异步处理（提取 / 滚动摘要），不阻塞回复
        String botId = BotContext.currentBotId();
        BotContext ctx = BotContext.get();
        memoryOrchestrator.afterReply(botId != null ? botId : "legacy", userId,
                ctx != null ? ctx.getTraceId() : null, messages);

        log.info("文本对话完成 userId={}", userId);
        return assistantContent;
    }

    /** 判断 FC 返回是否为意图协议标记（弱信号意图由 LLM 在 FC 首轮识别） */
    public static boolean isIntentMarker(String content) {
        return content != null
                && (content.startsWith("[VOICE]") || content.startsWith("[VOICE_SWITCH:"));
    }

    /**
     * 构建消息列表并注入长期记忆。
     * 注入位置：system 之后；三个槽位相互独立，不抢占配额。
     */
    private List<Map<String, Object>> buildMessagesWithMemory(String userId) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Map<String, Object> msg : history.getSnapshot(userId)) {
            messages.add(new HashMap<>(msg));
        }

        MemoryService.MemoryInjection injection = memoryService.getInjection(userId);
        int insertAt = (!messages.isEmpty() && "system".equals(messages.get(0).get("role"))) ? 1 : 0;

        if (injection.summary() != null && !injection.summary().isBlank()) {
            messages.add(insertAt++, systemMsg("[历史摘要] " + injection.summary()));
        }
        if (injection.facts() != null && !injection.facts().isEmpty()) {
            messages.add(insertAt++, systemMsg("[用户记忆]\n- " + String.join("\n- ", injection.facts())));
        }
        if (injection.notes() != null && !injection.notes().isEmpty()) {
            messages.add(insertAt, systemMsg("[用户笔记]\n- " + String.join("\n- ", injection.notes())));
        }
        return messages;
    }

    private static Map<String, Object> systemMsg(String content) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", "system");
        msg.put("content", content);
        return msg;
    }

    // ======================== 上下文持久化 ========================

    /**
     * 将 FC 循环中产生的 tool 消息和 assistant(tool_calls) 消息存入对话历史。
     * LLM 在下一轮对话中能读到 file_path 等上下文，避免跨轮断裂。
     */
    private void persistToolMessages(String userId, List<Map<String, Object>> messages) {
        for (Map<String, Object> msg : messages) {
            String role = (String) msg.get("role");
            if ("tool".equals(role) || ("assistant".equals(role) && msg.containsKey("tool_calls"))) {
                history.addRichMessage(userId, msg);
            }
        }
    }

    // ======================== 文件发送 ========================

    private void sendFiles(String userId,
                           LinkedHashSet<String> filePaths,
                           Set<String> explicitlySent) {
        for (String filePath : filePaths) {
            Path path = Paths.get(filePath);
            if (!Files.exists(path)) {
                log.warn("文件不存在，跳过: {}", filePath);
                continue;
            }
            try {
                byte[] fileBytes = Files.readAllBytes(path);
                String fileName = path.getFileName().toString();
                iintService.sendFile(BotContext.currentBotId(), userId, fileBytes, fileName, null);
                log.info("已发送文件: userId={}, file={}, explicit={}",
                        userId, fileName, explicitlySent.contains(filePath));
            } catch (IOException e) {
                log.error("读取文件失败: {}", filePath, e);
            } catch (Exception e) {
                log.error("发送文件失败: {}", filePath, e);
            }
        }
    }
}
