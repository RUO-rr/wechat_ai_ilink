package io.github.wangyangxu.ailink.rag;

import io.github.wangyangxu.ailink.model.KnowledgeChunk;
import io.github.wangyangxu.ailink.model.KnowledgeDocument;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 检索评测 —— 回答一个具体问题：<b>混合检索（向量 ∥ BM25）真的比单路好吗、好多少</b>。
 * <p>
 * 与 {@code VectorStoreBenchmarkTest}（测向量通道的<b>规模效应</b>）分工不同，这一层测的是<b>检索质量</b>：
 * 语料是 {@code src/test/resources/rag-eval} 下的真实公开文档，题目与标准答案在 {@code questions.tsv} 里人工标注。
 * <p>
 * <b>为什么用离线词法向量跑</b>：DashScope 需要密钥与网络，CI 里跑不起来；本地哈希向量
 * （{@code local-hashing-v1}，512 维）零网络、结果可复现 —— 同一份语料谁跑都是同一组数。
 * 真实模型下换的只是 {@link #embed(String)} 这一处，评测口径不变。
 * <p>
 * <b>断言口径</b>：离线哈希向量与 BM25 吃同一批 token，向量通道天然吃亏，所以断言守的是
 * 「混合不弱于它融合的向量通道、也不让关键词通道塌方」+ 三条绝对底线；「混合比单路好多少」
 * 由报告里的<b>融合权重敏感性表</b>回答，而不是靠一句口号。
 * <p>
 * 三个通道吃同一份语料、同一批问题：<b>vector</b> 只用向量余弦、<b>keyword</b> 只用 BM25、
 * <b>hybrid</b> 两路各取候选后归一化加权融合（生产口径）。指标是 Hit@1 / Hit@5 / MRR@5，
 * 文档级（命中来源文档）与片段级（命中含标准答案的片段）各一套。
 */
class RagEvaluationTest {

    /** 语料目录可覆盖（`-Drag.eval.corpus=<dir>`）：方便对着别的语料跑同一套指标 */
    private static final Path CORPUS_DIR = Path.of(System.getProperty("rag.eval.corpus",
            Path.of("src", "test", "resources", "rag-eval").toString()));
    private static final Path QUESTIONS_FILE = CORPUS_DIR.resolve("questions.tsv");
    private static final Path REPORT_FILE = Path.of(System.getProperty("rag.eval.report",
            Path.of("target", "bench", "rag-eval.md").toString()));

    /** 与生产一致：返回条数 4 → 评测取 5 更严格一点，候选倍数 3；融合权重可用 -Drag.eval.vectorWeight 覆盖 */
    private static final int TOP_K = 5;
    private static final int CANDIDATES = TOP_K * 3;
    private static final double VECTOR_WEIGHT = Double.parseDouble(
            System.getProperty("rag.eval.vectorWeight", "0.65"));
    /**
     * 融合权重敏感性扫描：离线词法向量与 BM25 吃的是同一批 token，向量权重越高越吃亏，
     * 只报一个权重说不清「混合比单路好多少」的边界，所以把曲线一起跑出来。
     */
    private static final double[] WEIGHT_SWEEP = {0.20d, 0.35d, 0.50d, 0.65d, 0.80d};
    private static final int DIMENSION = 512;
    private static final int CHUNK_MAX_CHARS = 800;
    private static final int CHUNK_OVERLAP_CHARS = 120;

    private final HashingEmbeddingModel embedder = new HashingEmbeddingModel(DIMENSION);
    private final TextChunker chunker = chunker();

    /** 语料中的一篇文档：id 用相对路径，检索命中后能直接对着来源核对 */
    private record Doc(String id, String title, String text) {}

    /** 一道题：期望文档（可多篇，任一命中即算对）+ 答案片段里必然出现的字面串（用于片段级判定与一致性校验） */
    private record Question(String id, String text, List<String> expectedDocs, String expectedContains) {}

    /** 一路检索结果（统一形状，便于三种通道同口径比较） */
    private record Ranked(KnowledgeVectorIndex.Entry entry, double score) {}

    /** 单题判定：两条命中线各自的排名（1 起，未命中记 0） */
    private record Outcome(int docRank, int chunkRank) {
        boolean docHit() { return docRank > 0; }
        boolean chunkHit() { return chunkRank > 0; }
    }

    /** 一组指标：Hit@1 / Hit@K / MRR */
    private record Metrics(String label, double hit1, double hitK, double mrr, int questions) {}

    @Test
    void hybridRetrievalAgainstSingleChannels() throws IOException {
        List<Doc> docs = loadCorpus();
        List<Question> questions = loadQuestions();
        RetrievalIndex index = buildIndex(docs);
        assertTrue(index.chunkCount() > 0, "语料没有产生任何片段，检查切分参数与语料内容");

        // 标准答案一致性校验：标注的片段必须真的能在期望文档里找到，否则是标注写错而不是检索失败
        verifyGroundTruth(index, questions);

        Map<String, List<Outcome>> perMode = new LinkedHashMap<>();
        for (String mode : List.of("vector", "keyword", "hybrid")) {
            List<Outcome> outcomes = new ArrayList<>(questions.size());
            for (Question question : questions) {
                outcomes.add(evaluate(question, search(mode, question.text(), index)));
            }
            perMode.put(mode, outcomes);
        }

        Map<String, Metrics> docLevel = new LinkedHashMap<>();
        Map<String, Metrics> chunkLevel = new LinkedHashMap<>();
        for (Map.Entry<String, List<Outcome>> entry : perMode.entrySet()) {
            docLevel.put(entry.getKey(), metrics(label(entry.getKey()) + " · 文档级",
                    entry.getValue(), Outcome::docRank));
            chunkLevel.put(entry.getKey(), metrics(label(entry.getKey()) + " · 片段级",
                    entry.getValue(), Outcome::chunkRank));
        }

        System.out.println("\n===== RAG 检索评测（语料 " + index.chunkCount() + " 片段 / " + questions.size() + " 题）=====");
        docLevel.forEach((mode, metrics) -> System.out.println("  " + metrics));
        chunkLevel.forEach((mode, metrics) -> System.out.println("  " + metrics));

        // 融合权重敏感性：同一批问题、同一份候选，只改向量权重（关键词权重 = 1 - w）
        Map<Double, Metrics> sweepDoc = new LinkedHashMap<>();
        Map<Double, Metrics> sweepChunk = new LinkedHashMap<>();
        for (double weight : WEIGHT_SWEEP) {
            List<Outcome> outcomes = new ArrayList<>(questions.size());
            for (Question question : questions) {
                outcomes.add(evaluate(question, search("hybrid", question.text(), index, weight)));
            }
            sweepDoc.put(weight, metrics("混合 w=" + fmt(weight) + " · 文档级", outcomes, Outcome::docRank));
            sweepChunk.put(weight, metrics("混合 w=" + fmt(weight) + " · 片段级", outcomes, Outcome::chunkRank));
        }
        System.out.println("  —— 融合权重扫描（向量权重 : 关键词权重）——");
        sweepDoc.forEach((weight, metrics) -> System.out.println("  " + metrics
                + " | 片段级 Hit@5=" + pct(sweepChunk.get(weight).hitK())));

        writeReport(index, questions, perMode, docLevel, chunkLevel, sweepDoc, sweepChunk);

        // 断言口径（离线词法向量）：混合至少要打赢它融合进来的向量通道，且不能让关键词通道塌方；
        // 「混合 ≥ 纯关键词」在这套口径下不成立 —— 哈希向量与 BM25 吃同一批 token，向量只是噪声来源，
        // 所以这里守的是绝对底线 + 两条相对下限，真实 embedding 模型的对照跑法见 README。
        Metrics hybridDoc = docLevel.get("hybrid");
        Metrics hybridChunk = chunkLevel.get("hybrid");
        assertTrue(hybridDoc.hitK() >= docLevel.get("vector").hitK(), "混合的文档级 Hit@5 不应低于纯向量");
        assertTrue(hybridChunk.hitK() >= chunkLevel.get("vector").hitK(), "混合的片段级 Hit@5 不应低于纯向量");
        assertTrue(hybridDoc.hitK() >= docLevel.get("keyword").hitK() - 0.10d,
                "混合的文档级 Hit@5 比纯关键词低超过 10 个百分点，说明融合在拖后腿");
        assertTrue(hybridDoc.hitK() >= 0.80d, "混合的文档级 Hit@5 低于底线 0.80");
        assertTrue(hybridChunk.hitK() >= 0.60d, "混合的片段级 Hit@5 低于底线 0.60");
        assertTrue(hybridDoc.mrr() > 0.80d, "混合的文档级 MRR 低于底线 0.80");
    }

    // ==================== 检索 ====================

    /** 三个通道同口径：候选数一致，只有「用哪几路、怎么融合」不同。 */
    private List<Ranked> search(String mode, String query, RetrievalIndex index) {
        return search(mode, query, index, VECTOR_WEIGHT);
    }

    /** 同一条检索路径，权重可覆盖：主表与敏感性扫描共用一套实现，避免两套口径。 */
    private List<Ranked> search(String mode, String query, RetrievalIndex index, double vectorWeight) {
        List<KnowledgeVectorIndex.Scored> vectorHits = "keyword".equals(mode)
                ? List.of()
                : index.searchVector(embed(query), HashingEmbeddingModel.MODEL_NAME, CANDIDATES);
        List<KnowledgeVectorIndex.Scored> keywordHits = "vector".equals(mode)
                ? List.of()
                : index.searchKeyword(query, CANDIDATES);

        List<Ranked> ranked = new ArrayList<>();
        if ("vector".equals(mode)) {
            vectorHits.forEach(hit -> ranked.add(new Ranked(hit.payload(), hit.score())));
        } else if ("keyword".equals(mode)) {
            keywordHits.forEach(hit -> ranked.add(new Ranked(hit.payload(), hit.score())));
        } else {
            HybridFusion.fuse(vectorHits, keywordHits, vectorWeight)
                    .forEach(fused -> ranked.add(new Ranked(fused.payload(), fused.score())));
        }
        ranked.sort(Comparator.comparingDouble(Ranked::score).reversed());
        return ranked.stream().limit(TOP_K).toList();
    }

    private Outcome evaluate(Question question, List<Ranked> hits) {
        int docRank = 0;
        int chunkRank = 0;
        for (int i = 0; i < hits.size(); i++) {
            KnowledgeVectorIndex.Entry entry = hits.get(i).entry();
            if (docRank == 0 && question.expectedDocs().contains(entry.sourcePath())) {
                docRank = i + 1;
            }
            if (chunkRank == 0 && entry.content().contains(question.expectedContains())) {
                chunkRank = i + 1;
            }
        }
        return new Outcome(docRank, chunkRank);
    }

    private static Metrics metrics(String label, List<Outcome> outcomes,
                                   java.util.function.ToIntFunction<Outcome> rank) {
        int hit1 = 0;
        int hitK = 0;
        double mrr = 0d;
        for (Outcome outcome : outcomes) {
            int value = rank.applyAsInt(outcome);
            if (value == 1) {
                hit1++;
            }
            if (value > 0) {
                hitK++;
                mrr += 1d / value;
            }
        }
        int total = outcomes.size();
        return new Metrics(label, (double) hit1 / total, (double) hitK / total, mrr / total, total);
    }

    private static String label(String mode) {
        return switch (mode) {
            case "vector" -> "向量余弦";
            case "keyword" -> "关键词 BM25";
            default -> "混合 0.65 : 0.35";
        };
    }

    // ==================== 语料与题目装载 ====================

    private List<Doc> loadCorpus() throws IOException {
        if (!Files.isDirectory(CORPUS_DIR)) {
            Assumptions.abort("评测语料目录不存在：" + CORPUS_DIR.toAbsolutePath());
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(CORPUS_DIR)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    // 只收各主题子目录里的文档：根目录下的 README/说明文件不算语料
                    .filter(path -> !CORPUS_DIR.equals(path.getParent()))
                    .sorted()
                    .toList();
        }
        List<Doc> docs = new ArrayList<>();
        for (Path file : files) {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            String id = CORPUS_DIR.relativize(file).toString().replace('\\', '/');
            docs.add(new Doc(id, frontMatter(raw, "title", id), stripFrontMatter(raw)));
        }
        assertFalse(docs.isEmpty(), "评测语料为空：" + CORPUS_DIR.toAbsolutePath());
        return docs;
    }

    private List<Question> loadQuestions() throws IOException {
        if (!Files.isRegularFile(QUESTIONS_FILE)) {
            Assumptions.abort("评测题目文件不存在：" + QUESTIONS_FILE.toAbsolutePath());
        }
        List<Question> questions = new ArrayList<>();
        for (String line : Files.readAllLines(QUESTIONS_FILE, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("id\t")) {
                continue;
            }
            String[] parts = line.split("\t");
            if (parts.length < 4) {
                throw new IllegalArgumentException("题目行字段不足（需要 id / 问题 / 期望文档 / 答案字面串）：" + line);
            }
            List<String> expected = Stream.of(parts[2].split(","))
                    .map(String::trim).filter(text -> !text.isEmpty()).toList();
            questions.add(new Question(parts[0].trim(), parts[1].trim(), expected, parts[3].trim()));
        }
        assertFalse(questions.isEmpty(), "评测题目为空：" + QUESTIONS_FILE.toAbsolutePath());
        return questions;
    }

    /**
     * 标准答案自检：标注的文档必须存在，标注的字面串必须真出现在那些文档的某个片段里。
     * 标注写错时直接失败并列出问题行 —— 否则指标掉的锅会算到检索头上。
     */
    private void verifyGroundTruth(RetrievalIndex index, List<Question> questions) {
        List<String> problems = new ArrayList<>();
        for (Question question : questions) {
            for (String docId : question.expectedDocs()) {
                List<KnowledgeVectorIndex.Entry> entries = index.entriesOfDocument(documentIdOf(docId));
                if (entries.isEmpty()) {
                    problems.add(question.id() + " 期望文档不存在或没有片段: " + docId);
                    continue;
                }
                boolean found = entries.stream().anyMatch(entry -> entry.content().contains(question.expectedContains()));
                if (!found) {
                    problems.add(question.id() + " 标注的答案字面串不在 " + docId + " 的任何片段里: " + question.expectedContains());
                }
            }
        }
        assertTrue(problems.isEmpty(), "标准答案与语料不一致，先修标注：\n  " + String.join("\n  ", problems));
    }

    private final Map<String, Long> documentIds = new LinkedHashMap<>();

    /** 文档 id（相对路径）→ 库内 documentId；评测内部用，装载时填一次。 */
    private long documentIdOf(String docId) {
        Long id = documentIds.get(docId);
        if (id == null) {
            throw new IllegalArgumentException("语料里没有这份文档: " + docId);
        }
        return id;
    }

    private RetrievalIndex buildIndex(List<Doc> docs) {
        List<KnowledgeChunk> chunks = new ArrayList<>();
        Map<Long, KnowledgeDocument> documentsById = new LinkedHashMap<>();
        long documentId = 1L;
        for (Doc doc : docs) {
            KnowledgeDocument document = new KnowledgeDocument(KnowledgeDocument.SOURCE_RESOURCE,
                    doc.id(), doc.title(), "eval-hash-" + documentId,
                    HashingEmbeddingModel.MODEL_NAME, DIMENSION);
            document.setId(documentId);
            documentsById.put(documentId, document);
            documentIds.put(doc.id(), documentId);
            for (TextChunker.Chunk piece : chunker.split(doc.text())) {
                chunks.add(new KnowledgeChunk(documentId, piece.index(), piece.heading(), piece.text(),
                        EmbeddingCodec.encode(embed(piece.text())), DIMENSION,
                        HashingEmbeddingModel.MODEL_NAME));
            }
            documentId++;
        }
        KnowledgeVectorIndex index = new KnowledgeVectorIndex(true);
        index.rebuild(chunks, documentsById);
        return new InMemoryRetrievalIndex(index);
    }

    private float[] embed(String text) {
        return embedder.embed(text).content().vector();
    }

    private static TextChunker chunker() {
        RagProperties props = new RagProperties();
        ReflectionTestUtils.setField(props, "chunkMaxChars", CHUNK_MAX_CHARS);
        ReflectionTestUtils.setField(props, "chunkOverlapChars", CHUNK_OVERLAP_CHARS);
        return new TextChunker(props);
    }

    private static String frontMatter(String raw, String key, String fallback) {
        String block = frontMatterBlock(raw);
        if (block == null) {
            return fallback;
        }
        for (String line : block.split("\n")) {
            if (line.startsWith(key + ":")) {
                return line.substring(key.length() + 1).trim();
            }
        }
        return fallback;
    }

    /** 取出 front-matter 正文块（--- 与 --- 之间）；没有就返回 null。 */
    private static String frontMatterBlock(String raw) {
        String text = raw.replace("\r\n", "\n");
        if (!text.startsWith("---")) {
            return null;
        }
        int end = text.indexOf("\n---", 3);
        return end < 0 ? null : text.substring(3, end);
    }

    private static String stripFrontMatter(String raw) {
        String text = raw.replace("\r\n", "\n");
        if (!text.startsWith("---")) {
            return text;
        }
        int end = text.indexOf("\n---", 3);
        if (end < 0) {
            return text;
        }
        int body = text.indexOf('\n', end + 1);
        return body < 0 ? "" : text.substring(body + 1);
    }

    // ==================== 报告 ====================

    private void writeReport(RetrievalIndex index, List<Question> questions,
                             Map<String, List<Outcome>> perMode,
                             Map<String, Metrics> docLevel, Map<String, Metrics> chunkLevel,
                             Map<Double, Metrics> sweepDoc, Map<Double, Metrics> sweepChunk) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# RAG 检索评测：混合 vs 单路\n\n");
        sb.append("由 `RagEvaluationTest` 生成。离线词法向量（`").append(HashingEmbeddingModel.MODEL_NAME)
                .append("`，").append(DIMENSION).append(" 维），三个通道吃同一份语料、同一批问题。\n\n");
        sb.append("- 语料 ").append(index.chunkCount()).append(" 个片段（公开文档，见 `sources.tsv`）\n");
        sb.append("- 题目 ").append(questions.size()).append(" 条，人工标注「期望文档 + 答案里的字面串」\n");
        sb.append("- 切分与生产一致：标题感知，maxChars=").append(CHUNK_MAX_CHARS)
                .append(" / overlap=").append(CHUNK_OVERLAP_CHARS).append("；候选 = topK × 3，融合权重 ")
                .append(fmt(VECTOR_WEIGHT)).append(" : ").append(fmt(1 - VECTOR_WEIGHT)).append("\n");
        sb.append("- 指标：Hit@1 / Hit@").append(TOP_K).append(" / MRR@").append(TOP_K)
                .append("；「文档级」= 命中来源文档，「片段级」= 命中含标准答案的片段\n\n");

        sb.append("## 总览\n\n");
        sb.append("| 通道 | 文档级 Hit@1 | 文档级 Hit@").append(TOP_K).append(" | 文档级 MRR | 片段级 Hit@1 | 片段级 Hit@")
                .append(TOP_K).append(" | 片段级 MRR |\n|---|---|---|---|---|---|---|\n");
        for (String mode : perMode.keySet()) {
            Metrics doc = docLevel.get(mode);
            Metrics chunk = chunkLevel.get(mode);
            sb.append("| ").append(label(mode))
                    .append(" | ").append(pct(doc.hit1())).append(" | ").append(pct(doc.hitK()))
                    .append(" | ").append(num(doc.mrr()))
                    .append(" | ").append(pct(chunk.hit1())).append(" | ").append(pct(chunk.hitK()))
                    .append(" | ").append(num(chunk.mrr())).append(" |\n");
        }

        sb.append("\n## 融合权重敏感性（同一批问题，只改向量权重 w，关键词权重 = 1 − w）\n\n");
        sb.append("| 向量权重 w | 文档级 Hit@1 | 文档级 Hit@").append(TOP_K)
                .append(" | 文档级 MRR | 片段级 Hit@1 | 片段级 Hit@").append(TOP_K).append(" | 片段级 MRR |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (Map.Entry<Double, Metrics> entry : sweepDoc.entrySet()) {
            Metrics doc = entry.getValue();
            Metrics chunk = sweepChunk.get(entry.getKey());
            sb.append("| ").append(fmt(entry.getKey()))
                    .append(" | ").append(pct(doc.hit1())).append(" | ").append(pct(doc.hitK()))
                    .append(" | ").append(num(doc.mrr()))
                    .append(" | ").append(pct(chunk.hit1())).append(" | ").append(pct(chunk.hitK()))
                    .append(" | ").append(num(chunk.mrr())).append(" |\n");
        }

        sb.append("\n## 逐题明细（片段级排名，✗ = top-").append(TOP_K).append(" 未命中）\n\n");
        sb.append("| 题目 | 期望文档 | 向量 | BM25 | 混合 |\n|---|---|---|---|---|\n");
        for (int i = 0; i < questions.size(); i++) {
            Question question = questions.get(i);
            sb.append("| ").append(question.text().replace("|", "\\|"))
                    .append(" | ").append(String.join(", ", question.expectedDocs()))
                    .append(" | ").append(rank(perMode.get("vector").get(i).chunkRank()))
                    .append(" | ").append(rank(perMode.get("keyword").get(i).chunkRank()))
                    .append(" | ").append(rank(perMode.get("hybrid").get(i).chunkRank()))
                    .append(" |\n");
        }

        Map.Entry<Double, Metrics> bestWeight = sweepDoc.entrySet().stream()
                .max(Comparator.comparingDouble(entry -> entry.getValue().hitK()))
                .orElseThrow();
        String bestChannel = docLevel.get("keyword").hitK() >= docLevel.get("vector").hitK() ? "关键词 BM25" : "向量余弦";
        double bestChannelHitK = Math.max(docLevel.get("keyword").hitK(), docLevel.get("vector").hitK());
        double gap = bestChannelHitK - docLevel.get("hybrid").hitK();
        sb.append("\n## 结论（离线词法向量口径）\n\n");
        sb.append("- 文档级 Hit@").append(TOP_K).append("：关键词 BM25 ").append(pct(docLevel.get("keyword").hitK()))
                .append("、向量 ").append(pct(docLevel.get("vector").hitK()))
                .append("、混合（w=").append(fmt(VECTOR_WEIGHT)).append("）")
                .append(pct(docLevel.get("hybrid").hitK())).append("；当前最强单路是 ").append(bestChannel)
                .append("，混合")
                .append(gap >= 0 ? ("落后 " + pct(gap)) : ("领先 " + pct(-gap)))
                .append("（文档级 Hit@").append(TOP_K).append("）\n");
        sb.append("- 文档级 Hit@1：混合 ").append(pct(docLevel.get("hybrid").hit1()))
                .append("、关键词 ").append(pct(docLevel.get("keyword").hit1()))
                .append("、向量 ").append(pct(docLevel.get("vector").hit1()))
                .append("；融合换来的是「头部排序更稳」，代价是尾部召回被向量通道稀释。\n");
        sb.append("- 权重扫描：文档级 Hit@").append(TOP_K).append(" 在 w ∈ [0.20, 0.80] 上几乎不动（最高 w=")
                .append(fmt(bestWeight.getKey())).append(" → ").append(pct(bestWeight.getValue().hitK()))
                .append("），说明离线哈希向量与 BM25 的候选高度重合，w 只影响 MRR 与 Hit@1，而这套口径下它无从体现。\n");
        sb.append("- 口径提醒：`").append(HashingEmbeddingModel.MODEL_NAME)
                .append("` 与 BM25 吃的是同一批 token，向量通道几乎只额外带来哈希噪声，所以这套离线口径天然偏向关键词通道；\n")
                .append("  要验证生产融合权重（0.65 : 0.35）需要用真实 embedding 模型重跑，换模型只改 `#embed` 一处。\n");

        Files.createDirectories(REPORT_FILE.getParent());
        Files.writeString(REPORT_FILE, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("  报告已写入: " + REPORT_FILE.toAbsolutePath());
    }

    private static String rank(int value) {
        return value > 0 ? String.valueOf(value) : "✗";
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String pct(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String num(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
