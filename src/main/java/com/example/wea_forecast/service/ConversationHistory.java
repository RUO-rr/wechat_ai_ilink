package com.example.wea_forecast.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ConversationHistory {

    private static final Logger log = LoggerFactory.getLogger(ConversationHistory.class);

    @Value("${llm.system-prompt}")
    private String systemPrompt;

    @Value("${llm.max-history:20}")
    private int maxHistory;

    private final ConcurrentHashMap<String, List<Map<String, String>>> conversations
            = new ConcurrentHashMap<>();

    public List<Map<String, String>> getOrCreate(String userId) {
        return conversations.computeIfAbsent(userId, k -> {
            List<Map<String, String>> h = new ArrayList<>();
            Map<String, String> sysMsg = new HashMap<>();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
            h.add(sysMsg);
            return h;
        });
    }

    public void addMessage(String userId, String role, String content) {
        List<Map<String, String>> history = conversations.get(userId);
        if (history == null) {
            log.warn("用户 {} 的对话历史不存在，先创建再添加消息", userId);
            history = getOrCreate(userId);
        }
        Map<String, String> msg = new HashMap<>();
        msg.put("role", role);
        msg.put("content", content);
        history.add(msg);
    }

    public List<Map<String, String>> getSnapshot(String userId) {
        List<Map<String, String>> history = conversations.get(userId);
        if (history == null) {
            return null;
        }
        return new ArrayList<>(history);
    }

    public void trim(String userId) {
        List<Map<String, String>> history = conversations.get(userId);
        if (history == null) {
            return;
        }
        int maxMessages = 1 + maxHistory * 2;
        while (history.size() > maxMessages) {
            history.remove(1);
            if (history.size() > 1) {
                history.remove(1);
            }
        }
    }

    public void clear(String userId) {
        conversations.remove(userId);
        log.info("已清除用户 {} 的对话历史", userId);
    }
}
