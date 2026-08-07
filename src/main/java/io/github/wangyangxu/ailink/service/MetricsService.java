package io.github.wangyangxu.ailink.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存指标服务 —— 消息链路计时与简易计数器。
 * <ul>
 *   <li>单条消息的统计通过 ThreadLocal 在消息处理线程内聚合（FC 轮次 / LLM 调用耗时）</li>
 *   <li>全局计数器与最近延迟环形缓冲供 {@code GET /api/metrics} 读取</li>
 * </ul>
 */
@Component
public class MetricsService {

    private static final int LATENCY_RING_SIZE = 200;

    /** 单条消息进行中的统计（消息处理线程内聚合） */
    private static final class MessageContext {
        final long startNanos = System.nanoTime();
        int fcRounds;
        int llmCalls;
        long llmTotalMs;
    }

    /** 单条消息统计结果（endMessage 返回，供链路计时日志） */
    public record MessageStats(long totalMs, int fcRounds, int llmCalls, long llmTotalMs) {}

    private final ThreadLocal<MessageContext> messageContext = new ThreadLocal<>();

    // ==================== 全局计数器 ====================
    private final AtomicLong totalMessages = new AtomicLong();
    private final Map<String, AtomicLong> messageByType = new ConcurrentHashMap<>();
    private final AtomicLong fcTotalRounds = new AtomicLong();
    private final AtomicLong fcMaxRounds = new AtomicLong();
    private final AtomicLong llmCalls = new AtomicLong();
    private final AtomicLong llmTotalMs = new AtomicLong();
    private final AtomicLong memoryTasks = new AtomicLong();
    private final AtomicLong memoryTaskTotalMs = new AtomicLong();
    private final AtomicLong memoryFailures = new AtomicLong();
    private final AtomicLong queueDrops = new AtomicLong();
    private final AtomicLong sessionLost = new AtomicLong();
    private final ArrayBlockingQueue<Long> recentLatencies = new ArrayBlockingQueue<>(LATENCY_RING_SIZE);

    // ==================== 消息链路计时 ====================

    /** 消息处理开始（MainController 入口调用） */
    public void beginMessage() {
        messageContext.set(new MessageContext());
    }

    /** 记录消息类型（text/image/voice/file），用于类型分布 */
    public void recordMessageType(String type) {
        totalMessages.incrementAndGet();
        messageByType.computeIfAbsent(type, k -> new AtomicLong()).incrementAndGet();
    }

    /** FC 每轮调用 LLM 后记录（在消息处理线程内聚合） */
    public void recordFcRound() {
        MessageContext ctx = messageContext.get();
        if (ctx != null) ctx.fcRounds++;
    }

    /** 记录一次 LLM 调用耗时（毫秒） */
    public void recordLlmCall(long ms) {
        MessageContext ctx = messageContext.get();
        if (ctx != null) {
            ctx.llmCalls++;
            ctx.llmTotalMs += ms;
        }
        llmCalls.incrementAndGet();
        llmTotalMs.addAndGet(ms);
    }

    /** 消息处理结束：聚合全局计数器并返回本条统计（供 traceId 日志） */
    public MessageStats endMessage() {
        MessageContext ctx = messageContext.get();
        messageContext.remove();
        if (ctx == null) {
            return new MessageStats(0, 0, 0, 0);
        }
        long totalMs = (System.nanoTime() - ctx.startNanos) / 1_000_000;
        fcTotalRounds.addAndGet(ctx.fcRounds);
        fcMaxRounds.accumulateAndGet(ctx.fcRounds, Math::max);
        offerLatency(totalMs);
        return new MessageStats(totalMs, ctx.fcRounds, ctx.llmCalls, ctx.llmTotalMs);
    }

    // ==================== 其他计数器 ====================

    public void recordMemoryTask(long ms, boolean ok) {
        memoryTasks.incrementAndGet();
        memoryTaskTotalMs.addAndGet(ms);
        if (!ok) memoryFailures.incrementAndGet();
    }

    public void recordQueueDrop() {
        queueDrops.incrementAndGet();
    }

    public void recordSessionLost() {
        sessionLost.incrementAndGet();
    }

    // ==================== 快照（/api/metrics） ====================

    public Map<String, Object> snapshot() {
        List<Long> lats = new ArrayList<>(recentLatencies);
        lats.sort(null);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalMessages", totalMessages.get());
        Map<String, Long> byType = new LinkedHashMap<>();
        messageByType.forEach((k, v) -> byType.put(k, v.get()));
        m.put("messageByType", byType);
        m.put("fcTotalRounds", fcTotalRounds.get());
        m.put("fcMaxRounds", fcMaxRounds.get());
        m.put("llmCalls", llmCalls.get());
        m.put("llmAvgMs", llmCalls.get() == 0 ? 0 : llmTotalMs.get() / llmCalls.get());
        m.put("avgLatencyMs", lats.isEmpty() ? 0 : lats.stream().mapToLong(Long::longValue).sum() / lats.size());
        m.put("p50LatencyMs", percentile(lats, 0.50));
        m.put("p95LatencyMs", percentile(lats, 0.95));
        m.put("memoryTasks", memoryTasks.get());
        m.put("memoryTaskAvgMs", memoryTasks.get() == 0 ? 0 : memoryTaskTotalMs.get() / memoryTasks.get());
        m.put("memoryFailures", memoryFailures.get());
        m.put("queueDrops", queueDrops.get());
        m.put("sessionLost", sessionLost.get());
        return m;
    }

    private void offerLatency(long ms) {
        if (!recentLatencies.offer(ms)) {
            recentLatencies.poll();
            recentLatencies.offer(ms);
        }
    }

    private static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }
}
