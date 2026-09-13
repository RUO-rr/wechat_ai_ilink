package io.github.wangyangxu.ailink.rag;

import io.github.wangyangxu.ailink.model.KnowledgeChunk;
import io.github.wangyangxu.ailink.model.KnowledgeDocument;
import io.qdrant.client.QdrantClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * 向量通道基准 —— 回答一个具体问题：<b>语料从一万涨到十万，自研内存索引与 Qdrant HNSW 差多少</b>。
 * <p>
 * 跑法（默认不跑，避免污染日常测试）：
 * <pre>
 *   mvn -B test -Dtest=VectorStoreBenchmarkTest -Dbench.enabled=true \
 *       -DargLine=-Xmx3g -Dbench.sizes=10000,100000 -Dbench.dim=256
 * </pre>
 * 产出：控制台表格 + {@code target/bench/vector-store-benchmark.md}。
 * <p>
 * <b>为什么用合成向量</b>：真实语料只有几百个片段，测不出规模效应；而调线上向量模型灌十万条又慢又费钱。
 * 这里用「簇结构 + 噪声」合成向量（比均匀随机更接近真实 embedding 的几何：语义相近的片段聚在一起），
 * 四种跑法吃的是同一份数据，因此相对性能与 recall 是可比的。
 * <p>
 * <b>recall@k 以暴力精确检索为基准</b>：同维度同归一化下暴力扫描就是精确解，
 * 所以 recall 衡量的是「HNSW 近似到什么程度」—— 这正是换向量库唯一要付的代价。
 */
class VectorStoreBenchmarkTest {

    private static final String MODEL_ID = "bench-model";

    @Test
    @EnabledIfSystemProperty(named = "bench.enabled", matches = "true")
    void compareInMemoryAgainstQdrant() throws Exception {
        int dimension = Integer.getInteger("bench.dim", 256);
        int queryCount = Integer.getInteger("bench.queries", 100);
        int topK = Integer.getInteger("bench.k", 10);
        int[] sizes = Arrays.stream(System.getProperty("bench.sizes", "10000").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).mapToInt(Integer::parseInt).toArray();

        StringBuilder table = new StringBuilder();
        table.append("| 语料片段 | 向量通道 | 建索引 | p50 | p95 | recall@").append(topK).append(" |\n");
        table.append("|---|---|---|---|---|---|\n");
        Map<String, String> memoryRows = new LinkedHashMap<>();
        Map<String, String> incrementalRows = new LinkedHashMap<>();

        for (int size : sizes) {
            runSize(size, dimension, queryCount, topK, table, memoryRows, incrementalRows);
        }

        System.out.println("\n===== 向量通道基准（dim=" + dimension + ", queries=" + queryCount + "）=====");
        System.out.println(table);
        memoryRows.forEach((key, value) -> System.out.println("  " + key + " = " + value));
        incrementalRows.forEach((key, value) -> System.out.println("  " + key + " = " + value));
        writeReport(table, memoryRows, incrementalRows, dimension, queryCount, sizes);
    }

    private void runSize(int size, int dimension, int queryCount, int topK,
                         StringBuilder table, Map<String, String> memoryRows,
                         Map<String, String> incrementalRows) throws Exception {
        Corpus corpus = Corpus.generate(size, dimension);
        Map<Long, KnowledgeDocument> documents = corpus.documents();
        Map<Long, List<KnowledgeChunk>> grouped = corpus.groupedChunks(MODEL_ID);

        // ---------- 堆占用：先量「关键词 + 元信息」，再量「向量也进堆」 ----------
        long baseline = usedHeapAfterGc();
        KnowledgeVectorIndex keywordOnly = new KnowledgeVectorIndex(false);
        keywordOnly.rebuild(flatten(grouped), documents);
        long keywordHeap = usedHeapAfterGc() - baseline;
        keywordOnly = null;
        System.gc();

        long beforeFull = usedHeapAfterGc();
        KnowledgeVectorIndex inMemory = new KnowledgeVectorIndex(true);
        long buildStart = System.nanoTime();
        inMemory.rebuild(flatten(grouped), documents);
        long inMemoryBuildMs = (System.nanoTime() - buildStart) / 1_000_000;
        long fullHeap = usedHeapAfterGc() - beforeFull;
        memoryRows.put(size + " 片段 · 关键词 + 元信息（堆）", mb(keywordHeap));
        memoryRows.put(size + " 片段 · 再加向量（堆）", mb(fullHeap));
        memoryRows.put(size + " 片段 · 切向量库可省下", mb(Math.max(0L, fullHeap - keywordHeap)));

        RetrievalIndex inMemoryIndex = new InMemoryRetrievalIndex(inMemory);

        // ---------- 查询集：取语料向量加噪声，模拟「语义接近但不等同」的真实查询 ----------
        float[][] queries = corpus.queries(queryCount);

        // ---------- 进程内：暴力检索 + 自研融合 ----------
        long[] exactNs = new long[queryCount];
        long[] hybridNs = new long[queryCount];
        List<List<String>> exactTop = new ArrayList<>(queryCount);
        for (int i = 0; i < queryCount; i++) {
            long t0 = System.nanoTime();
            List<KnowledgeVectorIndex.Scored> vectorHits = inMemoryIndex.searchVector(queries[i], MODEL_ID, topK * 3);
            long t1 = System.nanoTime();
            List<KnowledgeVectorIndex.Scored> keywordHits = inMemoryIndex.searchKeyword(corpus.queryText(i), topK * 3);
            HybridFusion.fuse(vectorHits, keywordHits, 0.65d);
            long t2 = System.nanoTime();
            exactNs[i] = t1 - t0;
            hybridNs[i] = t2 - t0;
            exactTop.add(vectorHits.stream().limit(topK).map(hit -> hit.payload().identityKey()).toList());
        }
        table.append(row(size, "进程内暴力（向量）", inMemoryBuildMs + " ms", p50(exactNs), p95(exactNs), "1.000（基准）"));
        table.append(row(size, "进程内暴力 + BM25 融合", inMemoryBuildMs + " ms", p50(hybridNs), p95(hybridNs), "1.000（基准）"));

        // ---------- Qdrant：同一份数据灌进去，再跑同一批查询 ----------
        String collection = "ai_ilink_bench_" + size + "_" + System.currentTimeMillis() % 100000;
        VectorStoreProperties props = qdrantProps(collection, dimension);
        QdrantVectorStoreFactory.Connection connection = QdrantVectorStoreFactory.open(props);
        QdrantClient client = connection.client();
        try {
            RetrievalIndex remoteIndex = new EmbeddingStoreRetrievalIndex(
                    new KnowledgeVectorIndex(false), connection.store(), connection.admin(), props);
            // 建库走生产同款的「启动全量重建」：关键词通道一次编译完，向量分批灌进 Qdrant。
            // 不逐文档 index() —— 那样每写一份文档都要全量重编译一次 BM25 倒排，成本 = 语料 × 文档数，
            // 十万级根本跑不完（10k 那轮耗时 20s、100k 那轮卡死）。增量写入的真实代价在本方法末尾单独量化。
            long remoteBuildStart = System.nanoTime();
            remoteIndex.rebuild(flatten(grouped), documents);
            long remoteBuildMs = (System.nanoTime() - remoteBuildStart) / 1_000_000;
            long points = QdrantVectorStoreFactory.countPoints(client, props);
            if (points != size) {
                throw new IllegalStateException("Qdrant 回灌点数不符：期望 " + size + "，实际 " + points);
            }

            long[] remoteNs = new long[queryCount];
            long[] remoteHybridNs = new long[queryCount];
            double recallSum = 0d;
            for (int i = 0; i < queryCount; i++) {
                long t0 = System.nanoTime();
                List<KnowledgeVectorIndex.Scored> hits = remoteIndex.searchVector(queries[i], MODEL_ID, topK);
                long t1 = System.nanoTime();
                List<KnowledgeVectorIndex.Scored> keywordHits = remoteIndex.searchKeyword(corpus.queryText(i), topK * 3);
                HybridFusion.fuse(hits, keywordHits, 0.65d);
                long t2 = System.nanoTime();
                remoteNs[i] = t1 - t0;
                remoteHybridNs[i] = t2 - t0;
                recallSum += recall(hits, exactTop.get(i), topK);
            }
            table.append(row(size, "Qdrant HNSW（向量）", remoteBuildMs + " ms", p50(remoteNs), p95(remoteNs),
                    String.format("%.3f", recallSum / queryCount)));
            table.append(row(size, "Qdrant 向量 + 内存 BM25 融合", remoteBuildMs + " ms", p50(remoteHybridNs),
                    p95(remoteHybridNs), String.format("%.3f", recallSum / queryCount)));
            System.out.println("  " + size + " 片段 · Qdrant 点位数 = " + points);
        } finally {
            try {
                client.deleteCollectionAsync(collection).get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // 清理失败不影响结论：集合名带时间戳后缀
            }
            client.close();
            connection = null;
        }

        // ---------- 增量写入代价：往已建好的语料里再追加 1 份文档（10 片段） ----------
        // 这条路径就是生产里「上传一份文档」走的 KnowledgeVectorIndex.upsert ：
        // 关键词通道 copy-on-write 整体重编译，因此单次写入成本随语料规模线性增长。
        int probes = size >= 100_000 ? 1 : 3;
        long incrementalStart = System.nanoTime();
        for (int i = 0; i < probes; i++) {
            long documentId = 8_000_000L + i;
            KnowledgeDocument extra = new KnowledgeDocument(KnowledgeDocument.SOURCE_USER_UPLOAD,
                    "bench/extra-" + documentId + ".md", "extra-" + documentId + ".md",
                    "hash-extra-" + documentId, MODEL_ID, dimension);
            extra.setId(documentId);
            inMemory.upsert(extra, extraChunks(documentId, dimension, corpus));
        }
        double incrementalMs = (System.nanoTime() - incrementalStart) / 1_000_000d / probes;
        incrementalRows.put(size + " 片段 · 追加 1 份文档（10 片段，走关键词通道全量重编译）",
                String.format("%.1f ms", incrementalMs));
    }

    /** 追加用片段：10 个片段共用一条语料向量 —— 这里只量写入路径成本，不关心检索质量。 */
    private static List<KnowledgeChunk> extraChunks(long documentId, int dimension, Corpus corpus) {
        String embedding = EmbeddingCodec.encode(corpus.sampleVector());
        List<KnowledgeChunk> chunks = new ArrayList<>(10);
        for (int i = 0; i < 10; i++) {
            chunks.add(new KnowledgeChunk(documentId, i, "章节 " + i,
                    syntheticText((int) (documentId + i)), embedding, dimension, MODEL_ID));
        }
        return chunks;
    }

    private static List<KnowledgeChunk> flatten(Map<Long, List<KnowledgeChunk>> grouped) {
        List<KnowledgeChunk> all = new ArrayList<>();
        grouped.values().forEach(all::addAll);
        return all;
    }

    private VectorStoreProperties qdrantProps(String collection, int dimension) {
        VectorStoreProperties props = new VectorStoreProperties();
        ReflectionTestUtils.setField(props, "provider", "qdrant");
        ReflectionTestUtils.setField(props, "host", "127.0.0.1");
        ReflectionTestUtils.setField(props, "port", 6334);
        ReflectionTestUtils.setField(props, "collection", collection);
        ReflectionTestUtils.setField(props, "dimension", dimension);
        ReflectionTestUtils.setField(props, "payloadTextKey", "text_segment");
        ReflectionTestUtils.setField(props, "requestTimeoutMs", 60_000L);
        // 基准要的就是「空集合 → 全量回灌」这条路径，所以打开 boot 回灌开关
        ReflectionTestUtils.setField(props, "rebuildOnBoot", true);
        return props;
    }

    private static double recall(List<KnowledgeVectorIndex.Scored> hits, List<String> expected, int topK) {
        if (expected.isEmpty()) {
            return 1d;
        }
        List<String> actual = hits.stream().limit(topK).map(hit -> hit.payload().identityKey()).toList();
        long found = expected.stream().filter(actual::contains).count();
        return (double) found / expected.size();
    }

    private static String row(int size, String path, String build, String p50, String p95, String recall) {
        return "| " + size + " | " + path + " | " + build + " | " + p50 + " | " + p95 + " | " + recall + " |\n";
    }

    private static String p50(long[] samples) {
        return percentile(samples, 0.50d);
    }

    private static String p95(long[] samples) {
        return percentile(samples, 0.95d);
    }

    private static String percentile(long[] samples, double quantile) {
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        int index = (int) Math.min(sorted.length - 1L, Math.round(quantile * (sorted.length - 1)));
        return String.format("%.2f ms", sorted[index] / 1_000_000d);
    }

    private static long usedHeapAfterGc() {
        for (int i = 0; i < 3; i++) {
            System.gc();
            try {
                Thread.sleep(80);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static String mb(long bytes) {
        return String.format("%.1f MB", bytes / 1024d / 1024d);
    }

    private static void writeReport(StringBuilder table, Map<String, String> memoryRows,
                                    Map<String, String> incrementalRows,
                                    int dimension, int queryCount, int[] sizes) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# 向量通道基准：进程内暴力检索 vs Qdrant HNSW\n\n");
        sb.append("由 `VectorStoreBenchmarkTest` 生成（`-Dbench.enabled=true`）。\n\n");
        sb.append("- 向量维度 ").append(dimension).append("，查询数 ").append(queryCount)
          .append("，语料规模 ").append(Arrays.toString(sizes)).append("\n");
        sb.append("- 语料为合成向量（簇结构 + 噪声）+ 等长合成片段，四种跑法吃同一份数据\n");
        sb.append("- recall 以进程内暴力检索的 top-k 结果为精确基准\n");
        sb.append("- 「建索引」= 从一堆片段建成整座索引（内存实现是编译倒排，Qdrant 是编译倒排 + 分批灌点），"
                + "走的是生产启动时的全量重建路径\n\n");
        sb.append(table).append("\n## 内存（JVM 堆，GC 后实测增量）\n\n");
        memoryRows.forEach((key, value) -> sb.append("- ").append(key).append(" = ").append(value).append("\n"));
        sb.append("\n## 增量写入（生产上传路径，非批量）\n\n");
        incrementalRows.forEach((key, value) -> sb.append("- ").append(key).append(" = ").append(value).append("\n"));
        sb.append("\n关键词通道是 copy-on-write：每写一份文档都整体重编译一次 BM25 倒排，"
                + "所以单次写入成本随语料规模线性增长。真实语料只有几百个片段时这份开销可以忽略，"
                + "但批量入库（十万级）必须走全量重建路径；要让增量写入也变成 O(新增) 需要给倒排加增量合并，"
                + "属于后续优化项。\n");
        Path out = Path.of("target", "bench", "vector-store-benchmark.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("  报告已写入: " + out.toAbsolutePath());
    }

    /** 合成语料：每 10 个片段归属一份文档，向量围绕该文档的簇心生成。 */
    private static final class Corpus {

        private final long[] documentIds;
        private final int[] chunkIndexes;
        private final String[] texts;
        private final float[][] vectors;
        private final int dimension;
        private final Random random;

        private Corpus(long[] documentIds, int[] chunkIndexes, String[] texts, float[][] vectors,
                       int dimension, Random random) {
            this.documentIds = documentIds;
            this.chunkIndexes = chunkIndexes;
            this.texts = texts;
            this.vectors = vectors;
            this.dimension = dimension;
            this.random = random;
        }

        static Corpus generate(int size, int dimension) {
            int documentCount = Math.max(1, size / 10);
            Random random = new Random(20260914L);
            float[][] centers = new float[documentCount][dimension];
            for (float[] center : centers) {
                fillRandom(center, random);
                normalize(center);
            }
            long[] documentIds = new long[size];
            int[] chunkIndexes = new int[size];
            String[] texts = new String[size];
            float[][] vectors = new float[size][dimension];
            for (int i = 0; i < size; i++) {
                documentIds[i] = i / 10;
                chunkIndexes[i] = i % 10;
                texts[i] = syntheticText(i);
                float[] vector = new float[dimension];
                float[] center = centers[i / 10];
                for (int d = 0; d < dimension; d++) {
                    vector[d] = (float) (center[d] + random.nextGaussian() * 0.25d);
                }
                normalize(vector);
                vectors[i] = vector;
            }
            return new Corpus(documentIds, chunkIndexes, texts, vectors, dimension, random);
        }

        Map<Long, KnowledgeDocument> documents() {
            Map<Long, KnowledgeDocument> map = new HashMap<>();
            for (long id : documentIds) {
                if (map.containsKey(id)) {
                    continue;
                }
                KnowledgeDocument document = new KnowledgeDocument(KnowledgeDocument.SOURCE_USER_UPLOAD,
                        "bench/doc-" + id + ".md", "doc-" + id + ".md", "hash-" + id, MODEL_ID, dimension);
                document.setId(id);
                map.put(id, document);
            }
            return map;
        }

        Map<Long, List<KnowledgeChunk>> groupedChunks(String modelId) {
            Map<Long, List<KnowledgeChunk>> grouped = new HashMap<>();
            for (int i = 0; i < documentIds.length; i++) {
                KnowledgeChunk chunk = new KnowledgeChunk(documentIds[i], chunkIndexes[i],
                        "章节 " + chunkIndexes[i], texts[i],
                        EmbeddingCodec.encode(vectors[i]), dimension, modelId);
                grouped.computeIfAbsent(documentIds[i], key -> new ArrayList<>()).add(chunk);
            }
            return grouped;
        }

        /** 查询 = 随机取一条语料向量加噪声，语义上「接近但不相同」。 */
        float[][] queries(int count) {
            float[][] queries = new float[count][dimension];
            for (int q = 0; q < count; q++) {
                float[] base = vectors[random.nextInt(vectors.length)];
                float[] query = new float[dimension];
                for (int d = 0; d < dimension; d++) {
                    query[d] = (float) (base[d] + random.nextGaussian() * 0.05d);
                }
                normalize(query);
                queries[q] = query;
            }
            return queries;
        }

        String queryText(int index) {
            return texts[index % texts.length];
        }

        /** 给增量写入测量取一条语料向量（只量成本，不关心它检索成什么样）。 */
        float[] sampleVector() {
            return vectors[0];
        }

        private static void fillRandom(float[] target, Random random) {
            for (int d = 0; d < target.length; d++) {
                target[d] = (float) random.nextGaussian();
            }
        }

        private static void normalize(float[] vector) {
            double sum = 0d;
            for (float value : vector) {
                sum += (double) value * value;
            }
            double norm = Math.sqrt(sum);
            if (norm == 0d) {
                return;
            }
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) (vector[i] / norm);
            }
        }
    }

    private static String syntheticText(int index) {
        return "片段 " + index + "：候选人负责支付网关重构与缓存治理，把响应时间从 800ms 降到 120ms，"
                + "并把上线流程自动化，单元测试覆盖率提升到 70% 以上。";
    }
}
