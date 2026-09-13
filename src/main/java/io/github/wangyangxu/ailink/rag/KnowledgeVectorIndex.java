package io.github.wangyangxu.ailink.rag;

import io.github.wangyangxu.ailink.model.KnowledgeChunk;
import io.github.wangyangxu.ailink.model.KnowledgeDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 进程内混合索引 —— 向量通道（余弦）+ 关键词通道（BM25）。
 * <p>
 * <b>为什么放内存</b>：当前语料是「内置资产 + 用户上传文档」，量级在千片段以内，
 * 全量载入内存后单次检索是毫秒级，比每次查库再算相似度便宜得多，也省掉一个向量数据库组件。
 * 索引以不可变快照发布（写入时整体重建后替换引用），读路径无锁、无半成品状态；
 * 语料规模长到内存吃不下时，替换本类实现（HNSW / pgvector / Milvus）即可，检索侧接口不变。
 * <p>
 * 两路召回的分数落在不同量纲上（余弦 0~1，BM25 无上界），因此这里只返回原始分，
 * 由 {@link KnowledgeRetriever} 各自归一化后加权融合。
 */
@Component
public class KnowledgeVectorIndex {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeVectorIndex.class);

    private static final double BM25_K1 = 1.2d;
    private static final double BM25_B = 0.75d;

    /** 索引条目：片段正文 + 文档元信息（引用展示用）+ 向量 + 词频（BM25 用） */
    public record Entry(long chunkId,
                        long documentId,
                        int chunkIndex,
                        String heading,
                        String content,
                        String sourcePath,
                        String title,
                        float[] vector,
                        String embeddingModel,
                        Map<String, Integer> termFreq,
                        int length) {

        /** 片段唯一标识：入库时未回填自增主键，用「文档 + 序号」稳定标识同一条片段。 */
        public String identityKey() {
            return documentId + "#" + chunkIndex;
        }
    }

    public record Scored(Entry entry, double score) {}

    public record Stats(int documents, int chunks, int vectorizedChunks, int dimensions) {}

    private record Snapshot(List<Entry> entries, Map<String, int[]> postings, double avgLength) {}

    private static final Snapshot EMPTY = new Snapshot(List.of(), Map.of(), 0d);

    private volatile Snapshot snapshot = EMPTY;

    // ==================== 写入（copy-on-write，读路径无锁） ====================

    /** 启动时全量重建：从库里的片段恢复索引，无需重新调用向量模型。 */
    public void rebuild(List<KnowledgeChunk> chunks, Map<Long, KnowledgeDocument> documentsById) {
        List<Entry> entries = new ArrayList<>(chunks.size());
        for (KnowledgeChunk chunk : chunks) {
            KnowledgeDocument doc = documentsById.get(chunk.getDocumentId());
            entries.add(toEntry(chunk, doc));
        }
        snapshot = build(entries);
        log.info("知识库索引重建完成: 片段={}, 文档={}", entries.size(), documentsById.size());
    }

    /** 单文档增量更新：先摘掉该文档旧片段，再挂上新片段。 */
    public synchronized void upsert(KnowledgeDocument document, List<KnowledgeChunk> chunks) {
        List<Entry> merged = new ArrayList<>();
        for (Entry e : snapshot.entries()) {
            if (e.documentId() != document.getId()) {
                merged.add(e);
            }
        }
        for (KnowledgeChunk chunk : chunks) {
            merged.add(toEntry(chunk, document));
        }
        snapshot = build(merged);
    }

    public synchronized void removeDocument(long documentId) {
        List<Entry> merged = new ArrayList<>();
        for (Entry e : snapshot.entries()) {
            if (e.documentId() != documentId) {
                merged.add(e);
            }
        }
        snapshot = build(merged);
    }

    private Entry toEntry(KnowledgeChunk chunk, KnowledgeDocument doc) {
        List<String> tokens = Tokenizers.tokenize(chunk.getContent());
        Map<String, Integer> termFreq = new HashMap<>();
        for (String token : tokens) {
            termFreq.merge(token, 1, Integer::sum);
        }
        return new Entry(
                chunk.getId() == null ? -1L : chunk.getId(),
                chunk.getDocumentId(),
                chunk.getChunkIndex(),
                chunk.getHeading(),
                chunk.getContent(),
                doc == null ? "unknown" : doc.getSourcePath(),
                doc == null ? "unknown" : doc.getTitle(),
                EmbeddingCodec.decode(chunk.getEmbedding()),
                chunk.getEmbeddingModel(),
                termFreq,
                tokens.size());
    }

    private static Snapshot build(List<Entry> entries) {
        if (entries.isEmpty()) {
            return EMPTY;
        }
        Map<String, List<Integer>> postingBuilder = new HashMap<>();
        long totalLength = 0L;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            totalLength += entry.length();
            for (String term : entry.termFreq().keySet()) {
                postingBuilder.computeIfAbsent(term, k -> new ArrayList<>()).add(i);
            }
        }
        Map<String, int[]> postings = new HashMap<>(postingBuilder.size() * 2);
        postingBuilder.forEach((term, list) -> {
            int[] arr = new int[list.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = list.get(i);
            }
            postings.put(term, arr);
        });
        return new Snapshot(List.copyOf(entries), Map.copyOf(postings),
                entries.isEmpty() ? 0d : (double) totalLength / entries.size());
    }

    // ==================== 读取 ====================

    /**
     * 向量召回：只比较同一向量模型、同一维度的片段。
     * 换过模型（如本地降级向量 → DashScope）但尚未重新索引的片段会被自然排除，不会污染排序。
     */
    public List<Scored> searchVector(float[] queryVector, String embeddingModel, int limit) {
        Snapshot current = snapshot;
        if (queryVector == null || current.entries().isEmpty() || limit <= 0) {
            return List.of();
        }
        PriorityQueue<Scored> top = new PriorityQueue<>(Comparator.comparingDouble(Scored::score));
        for (Entry entry : current.entries()) {
            if (entry.vector() == null || entry.vector().length != queryVector.length) {
                continue;
            }
            if (embeddingModel != null && !embeddingModel.equals(entry.embeddingModel())) {
                continue;
            }
            double score = EmbeddingCodec.cosine(queryVector, entry.vector());
            if (score <= 0d) {
                continue;
            }
            top.offer(new Scored(entry, score));
            if (top.size() > limit) {
                top.poll();
            }
        }
        return drain(top);
    }

    /** 关键词召回：BM25（k1=1.2, b=0.75）。中文字符二元组让「种族值修改」这类词也能命中。 */
    public List<Scored> searchKeyword(String query, int limit) {
        Snapshot current = snapshot;
        if (query == null || query.isBlank() || current.entries().isEmpty() || limit <= 0) {
            return List.of();
        }
        double[] scores = new double[current.entries().size()];
        int totalDocs = current.entries().size();
        for (String term : Tokenizers.tokenize(query)) {
            int[] posting = current.postings().get(term);
            if (posting == null) {
                continue;
            }
            double idf = Math.log(1d + (totalDocs - posting.length + 0.5d) / (posting.length + 0.5d));
            for (int entryIndex : posting) {
                Entry entry = current.entries().get(entryIndex);
                int tf = entry.termFreq().getOrDefault(term, 0);
                if (tf == 0) {
                    continue;
                }
                double denominator = tf + BM25_K1 * (1 - BM25_B + BM25_B * entry.length() / current.avgLength());
                scores[entryIndex] += idf * (tf * (BM25_K1 + 1)) / denominator;
            }
        }
        PriorityQueue<Scored> top = new PriorityQueue<>(Comparator.comparingDouble(Scored::score));
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] <= 0d) {
                continue;
            }
            top.offer(new Scored(current.entries().get(i), scores[i]));
            if (top.size() > limit) {
                top.poll();
            }
        }
        return drain(top);
    }

    private static List<Scored> drain(PriorityQueue<Scored> heap) {
        List<Scored> list = new ArrayList<>(heap);
        list.sort(Comparator.comparingDouble(Scored::score).reversed());
        return list;
    }

    public Stats stats() {
        Snapshot current = snapshot;
        int vectorized = 0;
        int dimensions = 0;
        for (Entry e : current.entries()) {
            if (e.vector() != null && e.vector().length > 0) {
                vectorized++;
                dimensions = e.vector().length;
            }
        }
        long documents = current.entries().stream().mapToLong(Entry::documentId).distinct().count();
        return new Stats((int) documents, current.entries().size(), vectorized, dimensions);
    }

    /** 某文档在索引中的片段（按序号排序），用于长文档的覆盖率采样。 */
    public List<Entry> entriesOfDocument(long documentId) {
        return snapshot.entries().stream()
                .filter(e -> e.documentId() == documentId)
                .sorted(Comparator.comparingInt(Entry::chunkIndex))
                .toList();
    }

    public boolean isEmpty() {
        return snapshot.entries().isEmpty();
    }

    public int size() {
        return snapshot.entries().size();
    }

}