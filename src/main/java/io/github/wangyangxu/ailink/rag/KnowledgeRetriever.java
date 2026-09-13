package io.github.wangyangxu.ailink.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.github.wangyangxu.ailink.service.MetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 知识检索链路 —— RAG 链路的读取侧，四段式：
 * <pre>
 *   查询向量化 ─┬─ 向量召回（余弦）
 *               └─ 关键词召回（BM25）
 *                      ↓ 分数各自归一化后加权融合（HybridFusion）
 *                   候选融合（按 文档#片段 去重）
 *                      ↓ 可选
 *                   精排（交叉编码器逐对打分）
 *                      ↓
 *                   去重配额 + 字符预算 → 带引用的上下文
 * </pre>
 * 每一段都可以单独降级：向量模型不可用 → 只跑关键词；精排不可用 → 按召回分排序；
 * 结果为空 → 返回空上下文，由调用方决定是否让模型直接回答。
 * <p>
 * <b>为什么不是单路向量检索</b>：中文技术文档里大量「专有名词、字段名、编号」这类字面命中更可靠
 * （向量会把它平滑掉），而语义泛化只有向量能覆盖，两条通道是互补而非替代。
 * <p>
 * 召回与融合的下沉实现与长期记忆共用（{@link HybridIndex} / {@link HybridFusion}），
 * 本类只保留知识库特有的东西：文档配额、字符预算与引用格式。
 */
@Service
public class KnowledgeRetriever {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetriever.class);

    /** 单条片段注入上限：再长的片段也只保留前 N 字，避免一条命中吃光上下文预算 */
    private static final int MAX_CHARS_PER_CHUNK = 1200;

    /** 精排分在最终排序中的权重（其余留给召回分，避免精排抖动直接推翻召回结果） */
    private static final double RERANK_WEIGHT = 0.8d;

    private final RagProperties props;
    private final KnowledgeVectorIndex index;
    private final KnowledgeIndexService indexService;
    private final EmbeddingModel embeddingModel;
    private final Reranker reranker;
    private final MetricsService metrics;

    public KnowledgeRetriever(RagProperties props,
                              KnowledgeVectorIndex index,
                              KnowledgeIndexService indexService,
                              EmbeddingModel ragEmbeddingModel,
                              Reranker ragReranker,
                              MetricsService metricsService) {
        this.props = props;
        this.index = index;
        this.indexService = indexService;
        this.embeddingModel = ragEmbeddingModel;
        this.reranker = ragReranker;
        this.metrics = metricsService;
    }

    // ==================== 对外入口 ====================

    public RetrievalResult retrieve(String query, int topK) {
        long start = System.nanoTime();
        int k = topK > 0 ? topK : props.getRetrieveTopK();
        if (!isAvailable() || query == null || query.isBlank()) {
            return RetrievalResult.empty(query);
        }

        int candidates = Math.max(k, k * Math.max(1, props.getCandidateMultiplier()));
        float[] queryVector = embedQuery(query);
        List<KnowledgeVectorIndex.Scored> vectorHits = queryVector == null
                ? List.of()
                : index.searchVector(queryVector, indexService.embeddingModelId(), candidates);
        List<KnowledgeVectorIndex.Scored> keywordHits = index.searchKeyword(query, candidates);

        List<HybridFusion.Fused<KnowledgeVectorIndex.Entry>> fused =
                HybridFusion.fuse(vectorHits, keywordHits, props.getVectorWeight());
        boolean reranked = rerank(query, fused);
        List<RetrievedChunk> selected = diversify(fused, k);
        String context = buildContext(selected);

        metrics.recordRetrieval((System.nanoTime() - start) / 1_000_000, selected.size());
        log.info("RAG 检索: query='{}' 向量命中={} 关键词命中={} 精排={} 返回={}",
                abbreviate(query), vectorHits.size(), keywordHits.size(), reranked, selected.size());
        return new RetrievalResult(query, selected, context);
    }

    /** 只要上下文文本的便捷入口（工具 / 文件问答复用）。 */
    public String retrieveContext(String query, int topK) {
        return retrieve(query, topK).contextText();
    }

    public boolean isAvailable() {
        return props.isEnabled() && !index.isEmpty();
    }

    // ==================== 召回与融合 ====================

    private float[] embedQuery(String query) {
        try {
            Response<Embedding> response = embeddingModel.embed(query);
            if (response == null || response.content() == null) {
                return null;
            }
            float[] vector = response.content().vector();
            EmbeddingCodec.normalize(vector);
            return vector;
        } catch (Exception e) {
            log.warn("查询向量化失败，本次仅走关键词召回: {}", e.getMessage());
            return null;
        }
    }

    /** @return 是否真的做了精排（区分「没开」和「开了但失败」） */
    private boolean rerank(String query, List<HybridFusion.Fused<KnowledgeVectorIndex.Entry>> hits) {
        if (hits.size() <= 1) {
            return false;
        }
        List<String> texts = new ArrayList<>(hits.size());
        for (HybridFusion.Fused<KnowledgeVectorIndex.Entry> hit : hits) {
            String heading = hit.payload().heading();
            texts.add(heading == null || heading.isBlank() ? hit.payload().content()
                    : heading + "\n" + hit.payload().content());
        }
        List<Double> scores;
        try {
            scores = reranker.score(query, texts);
        } catch (Exception e) {
            log.warn("精排调用失败，本次按召回分排序: {}", e.getMessage());
            return false;
        }
        if (scores == null || scores.size() != hits.size()) {
            return false;
        }
        double max = scores.stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue).max().orElse(0d);
        if (max <= 0d) {
            return false;
        }
        for (int i = 0; i < hits.size(); i++) {
            Double score = scores.get(i);
            if (score == null) {
                continue;
            }
            HybridFusion.Fused<KnowledgeVectorIndex.Entry> hit = hits.get(i);
            hit.score(RERANK_WEIGHT * (score / max) + (1 - RERANK_WEIGHT) * hit.score());
            hit.channel(HybridFusion.CHANNEL_RERANK);
        }
        hits.sort((a, b) -> Double.compare(b.score(), a.score()));
        return true;
    }

    /** 单文档配额：同一份文档最多占 maxPerDocument 条，避免一份长文档刷满结果。 */
    private List<RetrievedChunk> diversify(List<HybridFusion.Fused<KnowledgeVectorIndex.Entry>> hits, int topK) {
        Map<Long, Integer> perDocument = new HashMap<>();
        int limitPerDocument = Math.max(1, props.getMaxPerDocument());
        List<RetrievedChunk> selected = new ArrayList<>();
        for (HybridFusion.Fused<KnowledgeVectorIndex.Entry> hit : hits) {
            KnowledgeVectorIndex.Entry entry = hit.payload();
            int used = perDocument.getOrDefault(entry.documentId(), 0);
            if (used >= limitPerDocument) {
                continue;
            }
            perDocument.put(entry.documentId(), used + 1);
            selected.add(new RetrievedChunk(entry.documentId(), entry.chunkIndex(),
                    entry.sourcePath(), entry.title(), entry.heading(),
                    entry.content(), round(hit.score()), hit.channel()));
            if (selected.size() >= topK) {
                break;
            }
        }
        return selected;
    }

    // ==================== 上下文组装 ====================

    private String buildContext(List<RetrievedChunk> chunks) {
        if (chunks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[知识库检索结果] 以下是本地知识库中与问题最相关的片段（按相关性排序）。")
                .append("回答时优先依据这些片段；引用具体规则、数字或字段时，请注明来源文件。")
                .append("若片段不足以回答，请明确说明，不要臆测。\n\n");
        int budget = Math.max(600, props.getContextMaxChars());
        int index = 1;
        for (RetrievedChunk chunk : chunks) {
            String block = "【片段 " + index + "】来源：" + chunk.citation()
                    + "（相关性 " + String.format(Locale.ROOT, "%.2f", chunk.score())
                    + "，命中通道 " + chunk.channel() + "）\n"
                    + truncate(chunk.content(), MAX_CHARS_PER_CHUNK) + "\n\n";
            if (index > 1 && sb.length() + block.length() > budget) {
                break;
            }
            sb.append(block);
            index++;
        }
        return sb.toString().strip();
    }

    // ==================== 小工具 ====================

    private static double round(double value) {
        return Math.round(value * 1000d) / 1000d;
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "…";
    }

    private static String abbreviate(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 60 ? flat : flat.substring(0, 60) + "…";
    }
}