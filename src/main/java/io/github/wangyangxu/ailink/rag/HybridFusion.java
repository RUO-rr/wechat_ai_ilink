package io.github.wangyangxu.ailink.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 双通道分数融合 —— 向量分（余弦 0~1）与关键词分（BM25 无上界）量纲不同，
 * 各自按本次召回的最大值归一化后加权求和；两路都命中的条目合并成一条并标注命中通道。
 * <p>
 * <b>为什么按「本次最大值」归一化</b>：两路召回都只取 top-N，绝对分数本身没有可比性
 * （同一条内容在不同查询下的 BM25 分差几十倍是常态），相对排序才是稳定的。
 * <p>
 * <b>为什么面向 {@link Hit} 而不是具体记录</b>：知识库片段与长期记忆各有自己的条目类型，
 * 融合只关心「稳定 key + 业务对象 + 原始分」，用最小接口把两边接起来，
 * 谁的索引换了实现都不影响这段打分逻辑。
 * <p>
 * 融合结果是可变对象（{@code score} / {@code channel} 可被精排改写），
 * 因为精排要按「精排分 × 权重 + 召回分 × 权重」覆盖最终分。
 */
public final class HybridFusion {

    public static final String CHANNEL_VECTOR = "vector";
    public static final String CHANNEL_KEYWORD = "keyword";
    public static final String CHANNEL_HYBRID = "hybrid";
    public static final String CHANNEL_RERANK = "rerank";

    private HybridFusion() {}

    /** 一路召回命中的最小抽象 */
    public interface Hit<V> {
        String key();
        V payload();
        double score();
    }

    /** 融合后的候选 */
    public static final class Fused<V> {
        private final String key;
        private final V payload;
        private double vectorScore;
        private double keywordScore;
        private double score;
        private String channel = CHANNEL_HYBRID;

        private Fused(String key, V payload) {
            this.key = key;
            this.payload = payload;
        }

        public String key() { return key; }
        public V payload() { return payload; }
        public double vectorScore() { return vectorScore; }
        public double keywordScore() { return keywordScore; }
        public double score() { return score; }
        public String channel() { return channel; }

        public void score(double score) { this.score = score; }
        public void channel(String channel) { this.channel = channel; }
    }

    /**
     * 融合两路召回：按 key 合并去重 → 各自归一化 → 加权求和 → 按最终分倒序。
     * 任一路为空时退化为单通道结果（另一路的权重自然接管）。
     */
    public static <V> List<Fused<V>> fuse(List<? extends Hit<V>> vectorHits,
                                          List<? extends Hit<V>> keywordHits,
                                          double vectorWeight) {
        Map<String, Fused<V>> byKey = new LinkedHashMap<>();
        double maxVector = maxScore(vectorHits);
        double maxKeyword = maxScore(keywordHits);

        if (vectorHits != null) {
            for (Hit<V> hit : vectorHits) {
                Fused<V> fused = byKey.computeIfAbsent(hit.key(), key -> new Fused<>(hit.key(), hit.payload()));
                fused.vectorScore = normalize(hit.score(), maxVector);
            }
        }
        if (keywordHits != null) {
            for (Hit<V> hit : keywordHits) {
                Fused<V> fused = byKey.computeIfAbsent(hit.key(), key -> new Fused<>(hit.key(), hit.payload()));
                fused.keywordScore = normalize(hit.score(), maxKeyword);
            }
        }

        double weight = Math.max(0d, Math.min(1d, vectorWeight));
        List<Fused<V>> hits = new ArrayList<>(byKey.values());
        for (Fused<V> hit : hits) {
            hit.score = weight * hit.vectorScore + (1 - weight) * hit.keywordScore;
            hit.channel = hit.vectorScore > 0 && hit.keywordScore > 0 ? CHANNEL_HYBRID
                    : (hit.vectorScore > 0 ? CHANNEL_VECTOR : CHANNEL_KEYWORD);
        }
        hits.sort(Comparator.comparingDouble((Fused<V> hit) -> hit.score()).reversed());
        return hits;
    }

    private static double maxScore(List<? extends Hit<?>> hits) {
        double max = 0d;
        if (hits == null) {
            return max;
        }
        for (Hit<?> hit : hits) {
            max = Math.max(max, hit.score());
        }
        return max;
    }

    private static double normalize(double score, double max) {
        return max <= 0d ? 0d : Math.max(0d, Math.min(1d, score / max));
    }
}