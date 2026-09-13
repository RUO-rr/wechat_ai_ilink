package io.github.wangyangxu.ailink.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.UnaryOperator;

/**
 * 通用双通道索引内核 —— 向量通道（余弦）+ 关键词通道（BM25）。
 * <p>
 * <b>为什么不与业务绑定</b>：知识库片段与长期记忆对打分的要求完全一致（语义泛化 + 字面命中），
 * 差异只在「索引的是什么」。把打分内核抽到这里之后，两条链路共用同一份实现与同一套降级行为，
 * 而不是各写一份 BM25 —— 这是「长期记忆复用 RAG 检索基建」的落点。
 * <p>
 * 索引以不可变快照发布（写入整体重建后替换引用），读路径无锁、不会读到半个索引；
 * 两路召回的分数落在不同量纲上（余弦 0~1，BM25 无上界），因此这里只返回原始分，
 * 由 {@link HybridFusion} 各自归一化后加权融合。
 */
public class HybridIndex<V> {

    private static final double BM25_K1 = 1.2d;
    private static final double BM25_B = 0.75d;

    /** 索引条目：key 是稳定标识（去重 / 覆盖），text 参与 BM25，payload 是业务对象 */
    public record Doc<V>(String key, String text, float[] vector, String embeddingModel, V payload) {}

    /** 一路召回结果：实现 {@link HybridFusion.Hit}，与其它索引的命中一起参与融合 */
    public record Scored<V>(Doc<V> doc, double score) implements HybridFusion.Hit<V> {
        @Override
        public String key() { return doc.key(); }

        @Override
        public V payload() { return doc.payload(); }
    }

    public record Stats(int size, int vectorized, int dimensions) {}

    private record Compiled<V>(Doc<V> doc, Map<String, Integer> termFreq, int length) {}

    private record Snapshot<V>(List<Compiled<V>> docs, Map<String, int[]> postings, double avgLength) {}

    private static final Snapshot<?> EMPTY = new Snapshot<>(List.of(), Map.of(), 0d);

    /** 读路径只见完整快照：写入整体换引用，不回写已在读的快照 */
    private volatile Snapshot<V> snapshot = empty();

    @SuppressWarnings("unchecked")
    private static <V> Snapshot<V> empty() {
        return (Snapshot<V>) EMPTY;
    }

    // ==================== 写入（copy-on-write） ====================

    /** 全量替换：启动重建、整库灌入 */
    public synchronized void replace(List<Doc<V>> docs) {
        snapshot = compile(docs);
    }

    /**
     * 读-改-写：增量 upsert / 删除建立在当前快照上，完成后整体换引用。
     * 传入的是当前条目列表的副本，可以放心修改。
     */
    public synchronized void update(UnaryOperator<List<Doc<V>>> mutation) {
        List<Doc<V>> current = new ArrayList<>(snapshot.docs().size());
        for (Compiled<V> compiled : snapshot.docs()) {
            current.add(compiled.doc());
        }
        snapshot = compile(mutation.apply(current));
    }

    public List<Doc<V>> docs() {
        List<Doc<V>> out = new ArrayList<>(snapshot.docs().size());
        for (Compiled<V> compiled : snapshot.docs()) {
            out.add(compiled.doc());
        }
        return List.copyOf(out);
    }

    private static <V> Snapshot<V> compile(List<Doc<V>> docs) {
        if (docs == null || docs.isEmpty()) {
            return empty();
        }
        List<Compiled<V>> compiled = new ArrayList<>(docs.size());
        Map<String, List<Integer>> postingBuilder = new HashMap<>();
        long totalLength = 0L;
        for (int i = 0; i < docs.size(); i++) {
            Doc<V> doc = docs.get(i);
            List<String> tokens = Tokenizers.tokenize(doc.text());
            Map<String, Integer> termFreq = new HashMap<>();
            for (String token : tokens) {
                termFreq.merge(token, 1, Integer::sum);
            }
            totalLength += tokens.size();
            compiled.add(new Compiled<>(doc, termFreq, tokens.size()));
            for (String term : termFreq.keySet()) {
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
        return new Snapshot<>(List.copyOf(compiled), Map.copyOf(postings), (double) totalLength / compiled.size());
    }

    // ==================== 读取 ====================

    /**
     * 向量召回：只比较同一向量模型、同一维度的条目。
     * 换过模型（如本地降级向量 → DashScope）但尚未重新索引的条目会被自然排除，不会污染排序。
     */
    public List<Scored<V>> searchVector(float[] queryVector, String embeddingModel, int limit) {
        Snapshot<V> current = snapshot;
        if (queryVector == null || current.docs().isEmpty() || limit <= 0) {
            return List.of();
        }
        PriorityQueue<Scored<V>> top = new PriorityQueue<>(Comparator.comparingDouble((Scored<V> hit) -> hit.score()));
        for (Compiled<V> item : current.docs()) {
            float[] vector = item.doc().vector();
            if (vector == null || vector.length != queryVector.length) {
                continue;
            }
            if (embeddingModel != null && !embeddingModel.equals(item.doc().embeddingModel())) {
                continue;
            }
            double score = EmbeddingCodec.cosine(queryVector, vector);
            if (score <= 0d) {
                continue;
            }
            top.offer(new Scored<>(item.doc(), score));
            if (top.size() > limit) {
                top.poll();
            }
        }
        return drain(top);
    }

    /** 关键词召回：BM25（k1=1.2, b=0.75）。中文字符二元组让「种族值修改」这类词也能命中。 */
    public List<Scored<V>> searchKeyword(String query, int limit) {
        Snapshot<V> current = snapshot;
        if (query == null || query.isBlank() || current.docs().isEmpty() || limit <= 0) {
            return List.of();
        }
        double[] scores = new double[current.docs().size()];
        int totalDocs = current.docs().size();
        for (String term : Tokenizers.tokenize(query)) {
            int[] posting = current.postings().get(term);
            if (posting == null) {
                continue;
            }
            double idf = Math.log(1d + (totalDocs - posting.length + 0.5d) / (posting.length + 0.5d));
            for (int docIndex : posting) {
                Compiled<V> item = current.docs().get(docIndex);
                int tf = item.termFreq().getOrDefault(term, 0);
                if (tf == 0) {
                    continue;
                }
                double denominator = tf + BM25_K1 * (1 - BM25_B + BM25_B * item.length() / current.avgLength());
                scores[docIndex] += idf * (tf * (BM25_K1 + 1)) / denominator;
            }
        }
        PriorityQueue<Scored<V>> top = new PriorityQueue<>(Comparator.comparingDouble((Scored<V> hit) -> hit.score()));
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] <= 0d) {
                continue;
            }
            top.offer(new Scored<>(current.docs().get(i).doc(), scores[i]));
            if (top.size() > limit) {
                top.poll();
            }
        }
        return drain(top);
    }

    private static <V> List<Scored<V>> drain(PriorityQueue<Scored<V>> heap) {
        List<Scored<V>> list = new ArrayList<>(heap);
        list.sort(Comparator.comparingDouble((Scored<V> hit) -> hit.score()).reversed());
        return list;
    }

    public Stats stats() {
        Snapshot<V> current = snapshot;
        int vectorized = 0;
        int dimensions = 0;
        for (Compiled<V> item : current.docs()) {
            float[] vector = item.doc().vector();
            if (vector != null && vector.length > 0) {
                vectorized++;
                dimensions = vector.length;
            }
        }
        return new Stats(current.docs().size(), vectorized, dimensions);
    }

    public boolean isEmpty() {
        return snapshot.docs().isEmpty();
    }

    public int size() {
        return snapshot.docs().size();
    }
}