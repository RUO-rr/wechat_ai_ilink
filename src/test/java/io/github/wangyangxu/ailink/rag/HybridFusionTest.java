package io.github.wangyangxu.ailink.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 融合策略的行为约束 —— 两种融合：分数归一化加权（生产默认）与 RRF（排名融合）。
 * <p>
 * 单测盯的是两者的<b>性质</b>而不是具体分值：加权融合吃分数、RRF 只吃名次。
 * 打分质量的对照在 {@code RagEvaluationTest}（同一份语料、同一批题），这里只保证实现不跑偏。
 */
class HybridFusionTest {

    private record Hit(String key, double score) implements HybridFusion.Hit<String> {
        @Override
        public String key() { return key; }

        @Override
        public String payload() { return key; }

        @Override
        public double score() { return score; }
    }

    @Test
    void rrfRewardsItemsHitByBothChannels() {
        // a 两路都命中（各第 1），b 只在向量里第 2，c 只在关键词里第 2
        List<HybridFusion.Fused<String>> fused = HybridFusion.fuseRrf(
                List.of(new Hit("a", 0.9), new Hit("b", 0.8)),
                List.of(new Hit("a", 12.5), new Hit("c", 11.0)),
                HybridFusion.DEFAULT_RRF_K);

        assertEquals("a", fused.get(0).payload(), "两路都命中的条目应该排第一");
        assertEquals(HybridFusion.CHANNEL_HYBRID, fused.get(0).channel());
        assertEquals(3, fused.size(), "两路并集去重后应剩 a/b/c 三条");
        assertEquals(List.of("a", "b", "c"), fused.stream().map(HybridFusion.Fused::payload).toList(),
                "b 与 c 都是单路第 2 名、同分，按 key 兜底；a 因为两路都命中也排在最前");
    }

    @Test
    void rrfIgnoresScoreMagnitudeAndOnlyUsesRank() {
        // 向量侧 a 的分数是 b 的 1000 倍，但名次只差一位 —— RRF 下差距只来自名次
        List<HybridFusion.Fused<String>> rrf = HybridFusion.fuseRrf(
                List.of(new Hit("a", 1000d), new Hit("b", 1d)),
                List.of(),
                HybridFusion.DEFAULT_RRF_K);

        double gap = rrf.get(0).score() - rrf.get(1).score();
        assertEquals(1d / 61d - 1d / 62d, gap, 1e-9, "RRF 的差距只由名次决定，与分差无关");

        // 同样两路数据走加权融合时，分差被压到归一化后的 0/1 之间 —— 这就是两种口径的差别
        List<HybridFusion.Fused<String>> weighted = HybridFusion.fuse(
                List.of(new Hit("a", 1000d), new Hit("b", 1d)), List.of(), 0.65d);
        assertEquals(0.65d, weighted.get(0).score(), 1e-9);
        assertEquals(0.00065d, weighted.get(1).score(), 1e-9);
    }

    @Test
    void rrfBreaksExactTiesByKeySoResultsAreDeterministic() {
        // z 是向量侧第 1 名、a 是关键词侧第 1 名 —— 两条总分完全相同，只能按 key 兜底
        List<HybridFusion.Fused<String>> fused = HybridFusion.fuseRrf(
                List.of(new Hit("z", 0.9d)),
                List.of(new Hit("a", 5.0d)),
                HybridFusion.DEFAULT_RRF_K);

        assertEquals(List.of("a", "z"), fused.stream().map(HybridFusion.Fused::payload).toList(),
                "总分并列时按 key 升序兜底，保证同一份数据两次跑出同序");

        // 同一份输入重复调用必须同序（名次来自入参顺序，入参相同则结果相同）
        List<Hit> vector = List.of(new Hit("b", 1d), new Hit("a", 1d));
        List<Hit> keyword = List.of(new Hit("b", 1d), new Hit("a", 1d));
        assertEquals(
                HybridFusion.fuseRrf(vector, keyword, HybridFusion.DEFAULT_RRF_K).stream()
                        .map(HybridFusion.Fused::payload).toList(),
                HybridFusion.fuseRrf(vector, keyword, HybridFusion.DEFAULT_RRF_K).stream()
                        .map(HybridFusion.Fused::payload).toList());
    }

    @Test
    void bothFusionsDegradeToSingleChannelWhenOneSideIsEmpty() {
        List<Hit> keywordOnly = List.of(new Hit("x", 3d), new Hit("y", 2d));

        List<HybridFusion.Fused<String>> rrf = HybridFusion.fuseRrf(List.of(), keywordOnly, HybridFusion.DEFAULT_RRF_K);
        List<HybridFusion.Fused<String>> weighted = HybridFusion.fuse(List.of(), keywordOnly, 0.65d);

        assertEquals(List.of("x", "y"), rrf.stream().map(HybridFusion.Fused::payload).toList());
        assertEquals(List.of("x", "y"), weighted.stream().map(HybridFusion.Fused::payload).toList());
        assertTrue(rrf.stream().allMatch(hit -> HybridFusion.CHANNEL_KEYWORD.equals(hit.channel())));
        assertFalse(weighted.isEmpty());
        assertTrue(HybridFusion.fuseRrf(null, null, HybridFusion.DEFAULT_RRF_K).isEmpty());
    }

    @Test
    void rerankScoresAreNormalizedThenBlendedWithRecallScore() {
        // 召回分刻意都很小（RRF 的典型量级）：精排公式里召回分只占 0.2，且先各自归一化，所以不会被吞掉
        List<HybridFusion.Fused<String>> hits = HybridFusion.fuseRrf(
                List.of(new Hit("a", 0.02d), new Hit("b", 0.01d)),
                List.of(), HybridFusion.DEFAULT_RRF_K);

        boolean applied = HybridFusion.applyRerankScores(hits, List.of(1.0d, 9.0d),
                HybridFusion.DEFAULT_RERANK_WEIGHT);

        assertTrue(applied, "分数条数匹配且最大值大于 0，应当应用精排");
        assertEquals("b", hits.get(0).payload(), "精排把 b 顶上来：9.0/9.0×0.8 + 召回×0.2 大于 a 的对应值");
        assertTrue(hits.stream().allMatch(hit -> HybridFusion.CHANNEL_RERANK.equals(hit.channel())));
    }

    @Test
    void rerankIsSkippedWhenScoresAreMissingOrAllZero() {
        List<HybridFusion.Fused<String>> hits = HybridFusion.fuse(List.of(new Hit("a", 1d)), List.of(), 0.65d);

        assertFalse(HybridFusion.applyRerankScores(hits, null, 0.8d));
        assertFalse(HybridFusion.applyRerankScores(hits, List.of(), 0.8d), "条数不匹配应跳过");
        assertFalse(HybridFusion.applyRerankScores(hits, List.of(0d), 0.8d), "最大值非正应跳过");
        assertFalse(HybridFusion.applyRerankScores(null, List.of(1d), 0.8d));
        assertEquals(0.65d, hits.get(0).score(), 1e-9, "跳过后分数保持召回分原值");
    }
}
