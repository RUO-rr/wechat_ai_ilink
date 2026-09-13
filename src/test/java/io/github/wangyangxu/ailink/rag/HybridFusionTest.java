package io.github.wangyangxu.ailink.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridFusionTest {

    private static HybridIndex.Doc<String> doc(String key) {
        return new HybridIndex.Doc<>(key, key, null, null, key);
    }

    private static HybridIndex.Scored<String> hit(String key, double score) {
        return new HybridIndex.Scored<>(doc(key), score);
    }

    @Test
    void mergesBothChannelsByKeyAndMarksHybrid() {
        List<HybridFusion.Fused<String>> fused = HybridFusion.fuse(
                List.of(hit("a", 1.0d), hit("b", 0.5d)),
                List.of(hit("b", 10.0d), hit("c", 5.0d)),
                0.5d);

        assertEquals(3, fused.size(), "两路命中同一条时合并为一条");
        assertEquals("b", fused.get(0).payload());
        assertEquals(0.75d, fused.get(0).score(), 1e-9, "向量分 0.5 + 关键词分 1.0 各半");
        assertEquals(HybridFusion.CHANNEL_HYBRID, fused.get(0).channel());
        assertEquals(HybridFusion.CHANNEL_VECTOR, fused.get(1).channel());
        assertEquals(HybridFusion.CHANNEL_KEYWORD, fused.get(2).channel());
    }

    @Test
    void sortsByFusedScoreDescending() {
        List<HybridFusion.Fused<String>> fused = HybridFusion.fuse(
                List.of(hit("a", 1.0d)),
                List.of(hit("b", 10.0d)),
                0.9d);

        assertEquals("a", fused.get(0).payload(), "向量权重高时向量的头名排前");
        assertTrue(fused.get(0).score() > fused.get(1).score());
    }

    @Test
    void degradesToSingleChannelWhenOneSideIsEmpty() {
        List<HybridFusion.Fused<String>> keywordOnly = HybridFusion.fuse(List.of(), List.of(hit("a", 3.0d)), 0.65d);
        assertEquals(1, keywordOnly.size());
        assertEquals(0.35d, keywordOnly.get(0).score(), 1e-9, "单通道先归一化到 1，再按权重计入（1 - 0.65）");
        assertEquals(HybridFusion.CHANNEL_KEYWORD, keywordOnly.get(0).channel());

        List<HybridFusion.Fused<String>> vectorOnly = HybridFusion.fuse(List.of(hit("b", 0.4d)), null, 0.65d);
        assertEquals(1, vectorOnly.size());
        assertEquals(HybridFusion.CHANNEL_VECTOR, vectorOnly.get(0).channel());
    }

    @Test
    void clampsOutOfRangeVectorWeight() {
        List<HybridFusion.Fused<String>> vectorHeavy = HybridFusion.fuse(
                List.of(hit("v", 1.0d)), List.of(hit("k", 1.0d)), 5.0d);
        assertEquals("v", vectorHeavy.get(0).payload(), "权重被夹到 1.0：向量通道独占");

        List<HybridFusion.Fused<String>> keywordHeavy = HybridFusion.fuse(
                List.of(hit("v", 1.0d)), List.of(hit("k", 1.0d)), -1.0d);
        assertEquals("k", keywordHeavy.get(0).payload(), "权重被夹到 0.0：关键词通道独占");
    }

    @Test
    void zeroMaxScoreDoesNotProduceNotANumber() {
        List<HybridFusion.Fused<String>> fused = HybridFusion.fuse(
                List.of(hit("a", 0d)), List.of(hit("a", 0d)), 0.5d);

        assertEquals(1, fused.size());
        assertEquals(0d, fused.get(0).score(), 1e-9);
    }
}