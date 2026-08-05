package io.github.wangyangxu.ailink.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 记忆异步编排 —— 在"用户发送 + Bot 成功回复"后触发，不阻塞用户响应。
 * 单线程串行执行，避免同一用户并发提取/摘要互相干扰。
 */
@Service
public class MemoryOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(MemoryOrchestrator.class);

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "memory-worker");
        t.setDaemon(true);
        return t;
    });

    @Value("${llm.memory.summarize-every:10}")
    private int summarizeEvery;

    @Autowired
    private TurnCounterService turnCounterService;

    @Autowired
    private ConversationSummarizer summarizer;

    @Autowired
    private MemoryExtractionService extractionService;

    /** 用户消息发送 + Bot 成功回复后调用 */
    public void afterReply(String botId, String userId, List<Map<String, Object>> snapshot) {
        long turn = turnCounterService.increment(userId);
        List<Map<String, Object>> copy = snapshot == null ? List.of() : new ArrayList<>(snapshot);
        executor.submit(() -> {
            try {
                if (summarizeEvery > 0 && turn > 0 && turn % summarizeEvery == 0) {
                    summarizer.summarize(userId, copy);
                }
                extractionService.extract(botId, userId, copy);
            } catch (Exception e) {
                log.warn("记忆异步处理失败 userId={}: {}", userId, e.getMessage());
            }
        });
    }
}
