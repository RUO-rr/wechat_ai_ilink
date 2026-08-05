package io.github.wangyangxu.ailink.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 对话轮次计数器（会话级，Redis INCR 原子自增，RDB 持久化）。
 * "用户发送 + Bot 成功回复"记为 1 轮，用于滚动摘要的触发节奏。
 */
@Service
public class TurnCounterService {

    private static final Logger log = LoggerFactory.getLogger(TurnCounterService.class);

    private static final String KEY_PREFIX = "turn:counter:";

    @Autowired
    private StringRedisTemplate redisTemplate;

    public long increment(String userId) {
        try {
            Long v = redisTemplate.opsForValue().increment(KEY_PREFIX + userId);
            return v == null ? 0 : v;
        } catch (Exception e) {
            log.warn("轮次计数自增失败 userId={}: {}", userId, e.getMessage());
            return 0;
        }
    }
}
