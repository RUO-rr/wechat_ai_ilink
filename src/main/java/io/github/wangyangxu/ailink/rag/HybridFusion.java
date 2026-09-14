package io.github.wangyangxu.ailink.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    /** RRF 的平滑常数：取原论文（Cormack et al. 2009）的经验值 60，作用是把名次差距压平，避免第一名通吃 */
    public static final int DEFAULT_RRF_K = 60;

    /** 精排分与召回分的混合权重：精排占 0.8（精排更准但只在少量候选上可用，召回分留 0.2 做兜底） */
    public static final double DEFAULT_RERANK_WEIGHT = 0.8d;

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

    /**
     * 排名融合（Reciprocal Rank Fusion）：只吃名次、不吃分数 —— 第 r 名（r 从 1 起）贡献 1/(k + r)，
     * 两路都命中的条目自然拿到两份贡献。
     * <p>
     * <b>为什么需要它</b>：上面的加权融合要先按「本次召回的最大值」归一化，而向量余弦与 BM25 分的
     * 分布形状完全不同（前者挤在 0.8~0.95，后者方差极大）——用一个不稳定的基准去比较两种分，
     * 语料一换、候选数一改，基准就漂了。名次没有量纲，换模型、换语料都不用重新标定。
     * <p>
     * <b>代价</b>：名次融合丢掉了「第一名领先第二名多少」的信息（分差悬殊时加权融合能体现这一点）。
     * 所以它必须在评测里和加权融合比过才算数 —— 见 {@code docs/bench/rag-eval.md} 的融合策略对照。
     * <p>
     * 入参需按分数倒序（召回接口本来就返回倒序）；这里仍会各自排一次序，避免调用方顺序不同导致
     * 「同一份数据两次跑出不同名次」。
     */
    public static <V> List<Fused<V>> fuseRrf(List<? extends Hit<V>> vectorHits,
                                             List<? extends Hit<V>> keywordHits,
                                             int k) {
        int smooth = Math.max(1, k);
        Map<String, Fused<V>> byKey = new LinkedHashMap<>();
        applyRankContribution(vectorHits, smooth, true, byKey);
        applyRankContribution(keywordHits, smooth, false, byKey);

        List<Fused<V>> hits = new ArrayList<>(byKey.values());
        for (Fused<V> hit : hits) {
            hit.score = hit.vectorScore + hit.keywordScore;
            hit.channel = hit.vectorScore > 0 && hit.keywordScore > 0 ? CHANNEL_HYBRID
                    : (hit.vectorScore > 0 ? CHANNEL_VECTOR : CHANNEL_KEYWORD);
        }
        // 平手时按 key 兜底排序：名次融合经常出现同分（尤其小候选集），没有兜底键就不是确定性结果
        hits.sort(Comparator.comparingDouble((Fused<V> hit) -> hit.score()).reversed()
                .thenComparing(hit -> hit.key()));
        return hits;
    }

    private static <V> void applyRankContribution(List<? extends Hit<V>> hits, int smooth, boolean vectorSide,
                                                  Map<String, Fused<V>> byKey) {
        if (hits == null || hits.isEmpty()) {
            return;
        }
        List<? extends Hit<V>> ordered = new ArrayList<>(hits);
        ordered.sort(Comparator.comparingDouble((Hit<V> hit) -> hit.score()).reversed());
        for (int i = 0; i < ordered.size(); i++) {
            Hit<V> hit = ordered.get(i);
            Fused<V> fused = byKey.computeIfAbsent(hit.key(), key -> new Fused<>(hit.key(), hit.payload()));
            double contribution = 1d / (smooth + i + 1d);
            if (vectorSide) {
                fused.vectorScore = contribution;
            } else {
                fused.keywordScore = contribution;
            }
        }
    }

    /**
     * 把精排（rerank）分并入召回结果 —— <b>生产链路与评测共用这一份实现</b>，
     * 否则「评测量到的」和「线上跑的」是两套逻辑，结论没法用。
     * <p>
     * 规则：精排分先按<b>本次候选里的最大值</b>归一化（不同模型的分数量纲都不一样，
     * 有的给 0~1、有的给 0~10），再与召回分按 {@code rerankWeight : (1 - rerankWeight)} 加权，
     * 最后按最终分倒序。命中通道统一标成 {@code rerank}，方便日志里区分「这条是靠精排上来的」。
     *
     * @return 是否真的应用了精排；分数缺失、条数不匹配或最大值非正时返回 {@code false}，调用方按召回分排序
     */
    public static <V> boolean applyRerankScores(List<Fused<V>> hits, List<Double> scores, double rerankWeight) {
        if (hits == null || hits.isEmpty() || scores == null || scores.size() != hits.size()) {
            return false;
        }
        double max = scores.stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue).max().orElse(0d);
        if (max <= 0d) {
            return false;
        }
        double weight = Math.max(0d, Math.min(1d, rerankWeight));
        for (int i = 0; i < hits.size(); i++) {
            Double score = scores.get(i);
            if (score == null) {
                continue;
            }
            Fused<V> hit = hits.get(i);
            hit.score(weight * (score / max) + (1 - weight) * hit.score());
            hit.channel(CHANNEL_RERANK);
        }
        hits.sort(Comparator.comparingDouble((Fused<V> hit) -> hit.score()).reversed());
        return true;
    }

    private static double normalize(double score, double max) {
        return max <= 0d ? 0d : Math.max(0d, Math.min(1d, score / max));
    }
}
