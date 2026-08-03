package io.github.wangyangxu.ailink.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.wangyangxu.ailink.mapper.ChatMessageMapper;
import io.github.wangyangxu.ailink.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话历史管理 —— Redis 缓存 + MySQL 持久化双写。
 * <h3>缓存设计</h3>
 * <ul>
 *   <li>Redis key：{@code chat:history:{botId}:{userId}}，复合键隔离不同 Bot 下同一微信用户的会话</li>
 *   <li>TTL：30 分钟，由 Redis EXPIRE 管理（替代原 JVM LRU + TTL 方案）</li>
 *   <li>value：消息列表 JSON 数组（含 system prompt，存储时序列化）</li>
 *   <li>降级：Redis 不可用 → 直接走 DB 加载（不缓存）；DB 写入失败 → 仅内存/Redis，不阻断用户对话</li>
 * </ul>
 */
@Service
public class ConversationHistory {

    private static final Logger log = LoggerFactory.getLogger(ConversationHistory.class);

    /** Redis key 前缀 */
    private static final String CACHE_KEY_PREFIX = "chat:history:";
    /** 缓存 TTL：30 分钟 */
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    @Value("${llm.system-prompt}")
    private String systemPrompt;

    @Value("${llm.max-history:20}")
    private int maxHistory;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 每个会话 key 的本地锁，防止同 JVM 内同一会话并发读改写丢失更新 */
    private final ConcurrentHashMap<String, Object> keyLocks = new ConcurrentHashMap<>();

    /** 获取当前 botId（从 BotContext ThreadLocal） */
    private static String currentBotId() {
        String bid = BotContext.currentBotId();
        return bid != null ? bid : "legacy";
    }

    /**
     * 组合缓存 key：chat:history:{botId}:{userId}。
     * 多 Bot 场景下不同 Bot 的同名用户会话需隔离。
     */
    private static String cacheKey(String userId) {
        return CACHE_KEY_PREFIX + currentBotId() + ":" + userId;
    }

    private Object lockFor(String key) {
        return keyLocks.computeIfAbsent(key, k -> new Object());
    }

    // ==================== 公开 API ====================

    public List<Map<String, Object>> getOrCreate(String userId) {
        String key = cacheKey(userId);
        synchronized (lockFor(key)) {
            List<Map<String, Object>> cached = readFromRedis(key);
            if (cached != null) {
                return cached;
            }
            List<Map<String, Object>> messages = loadFromDb(userId);
            writeToRedis(key, messages);
            return messages;
        }
    }

    /** 添加纯文本消息（role + content） */
    public void addMessage(String userId, String role, String content) {
        String key = cacheKey(userId);
        synchronized (lockFor(key)) {
            List<Map<String, Object>> history = getOrCreate(userId);

            Map<String, Object> msg = new HashMap<>();
            msg.put("role", role);
            msg.put("content", content);
            history.add(msg);
            writeToRedis(key, history);

            // 双写 DB（system 消息不入库）
            if (!"system".equals(role)) {
                persistToDb(userId, role, content, null);
            }
        }
    }

    /** 添加富结构消息（tool / assistant(tool_calls) 等） */
    public void addRichMessage(String userId, Map<String, Object> msg) {
        String key = cacheKey(userId);
        synchronized (lockFor(key)) {
            List<Map<String, Object>> history = getOrCreate(userId);
            history.add(new HashMap<>(msg));
            writeToRedis(key, history);

            // 双写 DB：将整个 Map 序列化为 JSON 存入 rich_content
            String role = (String) msg.get("role");
            if (!"system".equals(role)) {
                String json = serializeToJson(msg);
                persistToDb(userId, role, null, json);
            }
        }
    }

    public List<Map<String, Object>> getSnapshot(String userId) {
        String key = cacheKey(userId);
        synchronized (lockFor(key)) {
            List<Map<String, Object>> cached = readFromRedis(key);
            if (cached != null) {
                return cached;
            }
            return loadFromDb(userId);
        }
    }

    public void trim(String userId) {
        String key = cacheKey(userId);
        synchronized (lockFor(key)) {
            List<Map<String, Object>> history = getOrCreate(userId);

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
            writeToRedis(key, history);
        }

        // DB 裁剪：保留最新 maxHistory*2 条
        try {
            int keepCount = maxHistory * 2;
            chatMessageMapper.trimOldMessages(currentBotId(), userId, keepCount);
        } catch (Exception e) {
            log.error("DB 裁剪失败 userId={}，降级跳过: {}", userId, e.getMessage());
        }
    }

    public void clear(String userId) {
        String key = cacheKey(userId);
        synchronized (lockFor(key)) {
            try {
                redisTemplate.delete(key);
            } catch (Exception e) {
                log.warn("Redis 清除失败 key={}，降级跳过: {}", key, e.getMessage());
            }
            keyLocks.remove(key);
        }
        try {
            chatMessageMapper.deleteByBotUser(currentBotId(), userId);
        } catch (Exception e) {
            log.error("DB 清除失败 userId={}，降级跳过: {}", userId, e.getMessage());
        }
        log.info("已清除 key={} 的对话历史（Redis+DB）", key);
    }

    // ==================== Redis 缓存 ====================

    /**
     * 从 Redis 读取消息列表。未命中或 Redis 异常 → 返回 null（调用方降级走 DB）。
     */
    private List<Map<String, Object>> readFromRedis(String key) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("Redis 读取失败 key={}，降级走 DB: {}", key, e.getMessage());
            return null;
        }
    }

    /**
     * 写入 Redis 并设置 TTL。Redis 异常时降级为不缓存（日志 WARN，不阻断）。
     */
    private void writeToRedis(String key, List<Map<String, Object>> messages) {
        try {
            redisTemplate.opsForValue().set(key, serializeToJson(messages), CACHE_TTL);
        } catch (Exception e) {
            log.warn("Redis 写入失败 key={}，降级为不缓存: {}", key, e.getMessage());
        }
    }

    // ==================== MySQL 持久化 ====================

    /**
     * 从 DB 加载消息并重建内存列表。
     * system prompt 不存储在 DB 中，每次动态注入到列表头部。
     */
    private List<Map<String, Object>> loadFromDb(String userId) {
        List<Map<String, Object>> messages = new ArrayList<>();

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

    /** 写入 DB，失败时降级为仅 Redis/内存（不抛异常） */
    private void persistToDb(String userId, String role, String content, String richContent) {
        try {
            ChatMessage entity = new ChatMessage(currentBotId(), userId, role, content, richContent);
            chatMessageMapper.insert(entity);
        } catch (Exception e) {
            log.error("DB 写入失败 key={} role={}，降级为仅 Redis: {}", cacheKey(userId), role, e.getMessage());
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

    /** 将消息列表序列化为 JSON 字符串 */
    private String serializeToJson(List<Map<String, Object>> messages) {
        try {
            return objectMapper.writeValueAsString(messages);
        } catch (Exception e) {
            log.error("JSON 序列化失败: {}", e.getMessage());
            return "[]";
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
}
