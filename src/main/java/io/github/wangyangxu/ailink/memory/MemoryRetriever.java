package io.github.wangyangxu.ailink.memory;

import io.github.wangyangxu.ailink.rag.HybridFusion;
import io.github.wangyangxu.ailink.rag.HybridIndex;
import io.github.wangyangxu.ailink.service.MetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 记忆检索 —— 长期记忆读路径的召回侧，复用知识库同一套「向量 ∥ BM25 + 归一化加权融合」。
 * <p>
 * <b>解决什么问题</b>：旧读路径按 id 倒序取最近 N 条，用户记忆一多，近期但与当前问题无关的记忆
 * 会挤掉真正相关的那条（「我上次说的那个项目」命中不了）。改为按相关性召回后，
 * 「最近」与「相关」各占各的配额（保底配额在 {@code MemoryService} 里，不在这里）。
 * <p>
 * <b>为什么不做精排</b>：记忆候选是个位数到几十条、每条都很短，精排的收益抵不上一次额外的模型往返；
 * 知识库那边语料大、片段长，精排才有意义。
 * <p>
 * <b>为什么用相对分排序、却用绝对分判重</b>：排序只关心候选之间的相对高低（归一化后融合）；
 * 判重问的是「这两条是不是同一件事」，必须用余弦绝对值，归一化分在这里没有意义。
 */
@Service
public class MemoryRetriever {

    private static final Logger log = LoggerFactory.getLogger(MemoryRetriever.class);

    private final MemoryProperties props;
    private final MemoryIndex index;
    private final MetricsService metrics;

    public MemoryRetriever(MemoryProperties props, MemoryIndex index, MetricsService metrics) {
        this.props = props;
        this.index = index;
        this.metrics = metrics;
    }

    /** 索引是否已就绪（无副作用）；false 表示本次读路径应退回 recency 注入 */
    public boolean isReady(String userId) {
        return props.isRecallEnabled() && index.isLoaded(userId);
    }

    /**
     * 按相关性召回：查询向量化 → 双通道召回 → 类型过滤 → 融合排序 → 截断。
     * 任何一步失败都返回空列表，由调用方用 recency 兜底，不抛异常。
     */
    public List<MemoryEntry> recall(String userId, String query, Set<String> memoryTypes, int limit) {
        if (!props.isRecallEnabled() || query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        if (!index.ensureLoaded(userId)) {
            return List.of();
        }
        long start = System.nanoTime();
        int candidates = Math.max(limit, limit * Math.max(1, props.getCandidateMultiplier()));
        float[] queryVector = index.embedOne(query);
        List<HybridIndex.Scored<MemoryEntry>> vectorHits = filter(
                queryVector == null ? List.of() : index.searchVector(userId, queryVector, candidates), memoryTypes);
        List<HybridIndex.Scored<MemoryEntry>> keywordHits =
                filter(index.searchKeyword(userId, query, candidates), memoryTypes);

        List<HybridFusion.Fused<MemoryEntry>> fused =
                HybridFusion.fuse(vectorHits, keywordHits, props.getVectorWeight());
        List<MemoryEntry> selected = new ArrayList<>(Math.min(limit, fused.size()));
        for (HybridFusion.Fused<MemoryEntry> hit : fused) {
            if (selected.size() >= limit) {
                break;
            }
            selected.add(hit.payload());
        }

        metrics.recordMemoryRecall((System.nanoTime() - start) / 1_000_000, selected.size());
        log.info("记忆召回: userId={} query='{}' 向量命中={} 关键词命中={} 返回={}",
                userId, abbreviate(query), vectorHits.size(), keywordHits.size(), selected.size());
        return selected;
    }

    /**
     * 写入侧去重：同用户下已存在语义几乎相同的记忆时，不再重复写入。
     * 只在 LLM 判定 {@code new} 时调用 —— {@code supersede} 是它已经看过旧记忆后的显式判断，不干预。
     */
    public boolean isDuplicate(String userId, String content) {
        if (!props.isDedupeEnabled() || content == null || content.isBlank()) {
            return false;
        }
        if (!index.ensureLoaded(userId)) {
            return false;
        }
        float[] vector = index.embedOne(content);
        if (vector == null) {
            return false;
        }
        List<HybridIndex.Scored<MemoryEntry>> hits = index.searchVector(userId, vector, 1);
        if (hits.isEmpty()) {
            return false;
        }
        double similarity = hits.get(0).score();
        if (similarity < props.getDedupeThreshold()) {
            return false;
        }
        log.info("记忆去重命中: userId={} 相似度={} 已有记忆 id={}", userId,
                Math.round(similarity * 1000d) / 1000d, hits.get(0).payload().id());
        return true;
    }

    private static List<HybridIndex.Scored<MemoryEntry>> filter(List<HybridIndex.Scored<MemoryEntry>> hits,
                                                                Set<String> memoryTypes) {
        if (memoryTypes == null || memoryTypes.isEmpty()) {
            return hits;
        }
        List<HybridIndex.Scored<MemoryEntry>> out = new ArrayList<>(hits.size());
        for (HybridIndex.Scored<MemoryEntry> hit : hits) {
            if (memoryTypes.contains(hit.payload().memoryType())) {
                out.add(hit);
            }
        }
        return out;
    }

    private static String abbreviate(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 40 ? flat : flat.substring(0, 40) + "…";
    }
}