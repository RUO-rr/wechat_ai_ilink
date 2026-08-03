package io.github.wangyangxu.ailink.service;

import io.github.wangyangxu.ailink.model.ChatMessage;
import io.github.wangyangxu.ailink.mapper.ChatMessageMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 对话历史管理 —— 内存 LRU 缓存 + SQLite 双写。
 * <h3>缓存 key 设计</h3>
 * 使用 {@code botId:userId} 复合键隔离不同 Bot 下同一微信用户的会话。
 * botId 从 {@link BotContext#currentBotId()}（ThreadLocal）获取。
 * <ul>
 *   <li>LRU 缓存：最多 {@code MAX_CACHE_SIZE} 个会话（按复合键），超出按最久未访问淘汰</li>
 *   <li>TTL：缓存条目超过 {@code CACHE_TTL_MS} 后强制从 DB 重新加载</li>
 *   <li>降级：DB 写入失败时仅保留内存，不阻断用户对话</li>
 *   <li>system prompt 不入库，每次从配置动态注入</li>
 * </ul>
 */
@Service
public class ConversationHistory {

    private static final Logger log = LoggerFactory.getLogger(ConversationHistory.class);

    /** LRU 缓存最大会话数（按 botId:userId 复合键计数） */
    private static final int MAX_CACHE_SIZE = 100;
    /** 缓存 TTL：30 分钟 */
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;

    @Value("${llm.system-prompt}")
    private String systemPrompt;

    @Value("${llm.max-history:20}")
    private int maxHistory;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 获取当前 botId（从 BotContext ThreadLocal） */
    private static String currentBotId() {
        String bid = BotContext.currentBotId();
        return bid != null ? bid : "legacy";
    }

    /**
     * 组合缓存 key：botId:userId。
     * 多 Bot 场景下不同 Bot 的同名用户会话需隔离。
     */
    private static String cacheKey(String userId) {
        return currentBotId() + ":" + userId;
    }

    // ==================== LRU 缓存结构 ====================

    /** 缓存条目：消息列表 + 加载时间戳 */
    private static class CacheEntry {
        final List<Map<String, Object>> messages;
        long loadedAt;

        CacheEntry(List<Map<String, Object>> messages) {
            this.messages = messages;
            this.loadedAt = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - loadedAt > CACHE_TTL_MS;
        }
    }

    /** accessOrder=true 实现 LRU；超出 MAX_CACHE_SIZE 自动淘汰最久未访问的条目 */
    private final LinkedHashMap<String, CacheEntry> cache =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                    boolean evict = size() > MAX_CACHE_SIZE;
                    if (evict) {
                        log.info("LRU 淘汰 key={} 的缓存", eldest.getKey());
                    }
                    return evict;
                }
            };

    // ==================== 公开 API ====================

    public List<Map<String, Object>> getOrCreate(String userId) {
        String key = cacheKey(userId);
        synchronized (cache) {
            CacheEntry entry = cache.get(key);

            // 缓存命中且未过期 → 直接返回
            if (entry != null && !entry.isExpired()) {
                return entry.messages;
            }

            // 缓存过期 → 打日志
            if (entry != null) {
                log.info("key={} 缓存已过期(TTL {}ms)，从 DB 重新加载", key, CACHE_TTL_MS);
            }

            // 从 DB 加载
            List<Map<String, Object>> messages = loadFromDb(userId);
            CacheEntry newEntry = new CacheEntry(messages);
            cache.put(key, newEntry);
            return messages;
        }
    }

    /** 添加纯文本消息（role + content） */
    public void addMessage(String userId, String role, String content) {
        List<Map<String, Object>> history;
        synchronized (cache) {
            history = getOrCreate(userId);
        }

        Map<String, Object> msg = new HashMap<>();
        msg.put("role", role);
        msg.put("content", content);

        synchronized (history) {
            history.add(msg);
        }

        // 双写 DB（system 消息不入库）
        if (!"system".equals(role)) {
            persistToDb(userId, role, content, null);
        }
    }

    /** 添加富结构消息（tool / assistant(tool_calls) 等） */
    public void addRichMessage(String userId, Map<String, Object> msg) {
        List<Map<String, Object>> history;
        synchronized (cache) {
            history = getOrCreate(userId);
        }

        Map<String, Object> copy = new HashMap<>(msg);
        synchronized (history) {
            history.add(copy);
        }

        // 双写 DB：将整个 Map 序列化为 JSON 存入 rich_content
        String role = (String) copy.get("role");
        if (!"system".equals(role)) {
            String json = serializeToJson(copy);
            persistToDb(userId, role, null, json);
        }
    }

    public List<Map<String, Object>> getSnapshot(String userId) {
        synchronized (cache) {
            CacheEntry entry = cache.get(cacheKey(userId));
            if (entry == null) {
                return Collections.emptyList();
            }
            synchronized (entry.messages) {
                return new ArrayList<>(entry.messages);
            }
        }
    }

    public void trim(String userId) {
        String key = cacheKey(userId);
        synchronized (cache) {
            CacheEntry entry = cache.get(key);
            if (entry == null) {
                return;
            }
            List<Map<String, Object>> history = entry.messages;
            synchronized (history) {
                // 内存裁剪：保留 system + 最近 maxHistory*2 条非 system 消息
                int maxMessages = 1 + maxHistory * 2;
                while (history.size() > maxMessages) {
                    history.remove(1);
                    if (history.size() > 1) {
                        history.remove(1);
                    }
                }
                // 裁剪后修复：移除开头孤立的 tool 消息（其对应的 assistant(tool_calls) 已被裁掉）
                sanitizeOrphanToolMessages(history);
            }
        }

        // DB 裁剪：保留最新 maxHistory*2 条
        try {
            int keepCount = maxHistory * 2;
            chatMessageMapper.trimOldMessages(currentBotId(), userId, keepCount);
        } catch (Exception e) {
            log.error("DB 裁剪失败 userId={}，降级跳过: {}", userId, e.getMessage());
        }
    }

    /**
     * 移除消息列表开头（system 之后）孤立的 tool 消息。
     * 当 trim 把 assistant(tool_calls) 裁掉后，紧跟的 tool 消息变成非法序列，
     * 需要在下一次 API 调用前清除。
     */
    private static void sanitizeOrphanToolMessages(List<Map<String, Object>> history) {
        // 从 index 1（跳过 system）开始，移除连续的 tool 消息
        int i = 1;
        while (i < history.size()) {
            String role = (String) history.get(i).get("role");
            if ("tool".equals(role)) {
                history.remove(i);
                // 不移除 i，因为下一条消息顶替了当前位置
            } else {
                break;
            }
        }
    }

    public void clear(String userId) {
        synchronized (cache) {
            cache.remove(cacheKey(userId));
        }
        try {
            chatMessageMapper.deleteByBotUser(currentBotId(), userId);
        } catch (Exception e) {
            log.error("DB 清除失败 userId={}，降级跳过: {}", userId, e.getMessage());
        }
        log.info("已清除 key={} 的对话历史（内存+DB）", cacheKey(userId));
    }

    // ==================== 内部方法 ====================

    /**
     * 从 DB 加载消息并重建内存列表。
     * system prompt 不存储在 DB 中，每次动态注入到列表头部。
     */
    private List<Map<String, Object>> loadFromDb(String userId) {
        List<Map<String, Object>> messages = Collections.synchronizedList(new ArrayList<>());

        // 注入 system prompt
        Map<String, Object> sysMsg = new HashMap<>();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt);
        messages.add(sysMsg);

        try {
            List<ChatMessage> dbMessages = chatMessageMapper.findByBotUser(currentBotId(), userId);
            if (dbMessages != null && !dbMessages.isEmpty()) {
                log.info("从 DB 恢复 key={} 的 {} 条消息", cacheKey(userId), dbMessages.size());
                for (ChatMessage cm : dbMessages) {
                    messages.add(deserializeToMap(cm));
                }
            }
        } catch (Exception e) {
            log.error("从 DB 加载 key={} 消息失败，降级为空历史: {}", cacheKey(userId), e.getMessage());
        }

        return messages;
    }

    /** 写入 DB，失败时降级为仅内存（不抛异常） */
    private void persistToDb(String userId, String role, String content, String richContent) {
        try {
            ChatMessage entity = new ChatMessage(currentBotId(), userId, role, content, richContent);
            chatMessageMapper.insert(entity);
        } catch (Exception e) {
            log.error("DB 写入失败 key={} role={}，降级为仅内存: {}", cacheKey(userId), role, e.getMessage());
        }
    }

    /** 将 Map 序列化为 JSON 字符串 */
    private String serializeToJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            log.error("JSON 序列化失败: {}", e.getMessage());
            return null;
        }
    }

    /** 将 DB 记录反序列化为 Map（优先 rich_content，其次 content） */
    private Map<String, Object> deserializeToMap(ChatMessage cm) {
        if (cm.getRichContent() != null && !cm.getRichContent().isBlank()) {
            try {
                return objectMapper.readValue(cm.getRichContent(),
                        new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                log.error("JSON 反序列化失败 id={}，降级为纯文本: {}", cm.getId(), e.getMessage());
            }
        }
        // 纯文本消息
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", cm.getRole());
        msg.put("content", cm.getContent());
        return msg;
    }
}
