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
}
