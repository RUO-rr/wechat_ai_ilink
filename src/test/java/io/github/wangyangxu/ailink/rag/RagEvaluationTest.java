package io.github.wangyangxu.ailink.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.github.wangyangxu.ailink.model.KnowledgeChunk;
import io.github.wangyangxu.ailink.model.KnowledgeDocument;
import io.github.wangyangxu.ailink.config.RagConfiguration;
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
    /** 框架原生链路里用元数据把片段挂回来源文档（框架不认识我们的相对路径 id） */
    private static final String DOC_ID_KEY = "doc_id";
    /**
     * 向量模型口径：{@code local}（默认，离线词法哈希向量，零网络、进 CI）或
     * {@code dashscope}（真实语义向量，需要 {@code RAG_EMBEDDING_API_KEY}／{@code LLM_STT_API_KEY}）。
     * 后者是「混合检索到底行不行」的判决性实验：换成真模型再跑一遍同一批题。
     */
    private static final String EVAL_MODEL = System.getProperty("rag.eval.model", "local");
    /**
     * 精排口径：默认 {@code off}（CI 离线跑）；{@code dashscope} 时用生产的 Reranker 装配
     * （gte-rerank-v2，需要 DashScope key）跑「融合 + 精排」两种组合。
     * 精排模型没有本地替身，所以这条只在有 key 时才有数 —— 报告里会写明「未跑」。
     */
    private static final String EVAL_RERANK = System.getProperty("rag.eval.rerank", "off");
    private static final int EMBED_BATCH_SIZE = 10;
    private static final String COHORT_LITERAL = "literal";
    private static final String COHORT_PARAPHRASE = "paraphrase";
    /** RRF 的平滑常数，与生产默认值一致（-Drag.eval.rrfK 可覆盖，用来做敏感性验证） */
    private static final int RRF_K = Integer.parseInt(System.getProperty("rag.eval.rrfK",
            String.valueOf(HybridFusion.DEFAULT_RRF_K)));

    private EmbeddingModel embedder = new HashingEmbeddingModel(DIMENSION);
    private String embeddingModelId = HashingEmbeddingModel.MODEL_NAME;
    private int embeddingDimension = DIMENSION;
    private Reranker reranker = Reranker.noop();
    private boolean rerankAvailable;
    private final TextSplitter splitter = splitter("self");

    /** 语料中的一篇文档：id 用相对路径，检索命中后能直接对着来源核对 */
    private record Doc(String id, String title, String text) {}

    /**
     * 一道题：期望文档（可多篇，任一命中即算对）+ 答案片段里必然出现的字面串（用于片段级判定与一致性校验）
     * + 题目风格分组（{@code literal} 原文措辞 / {@code paraphrase} 口语改写）。
     * <p>
     * 分组是为了回答一个问题：混合检索赢/输，有多少来自检索本身、有多少来自「题目复用了文档措辞」。
     * 现有题库长期只有词面重叠型题目，等于把比较放在了 BM25 的主场（D-17 遗留 ①）。
     */
    private record Question(String id, String text, List<String> expectedDocs, String expectedContains,
                            String cohort) {}

    /** 一路检索结果（统一形状，便于三种通道同口径比较） */
    private record Ranked(KnowledgeVectorIndex.Entry entry, double score) {}

    /** 参与对照的检索口径：两路单通道 + 加权融合（生产默认） + RRF（排名融合） */
    private static final List<String> MODES = List.of("vector", "keyword", "hybrid", "rrf");

    /** 本次实际跑的口径：配了精排才有后两行（融合 + 精排） */
    private List<String> modes() {
        if (!rerankAvailable) {
            return MODES;
        }
        List<String> all = new ArrayList<>(MODES);
        all.add("hybrid+rerank");
        all.add("rrf+rerank");
        return all;
    }

    /** 单题判定：两条命中线各自的排名（1 起，未命中记 0） */
    private record Outcome(int docRank, int chunkRank) {
        boolean docHit() { return docRank > 0; }
        boolean chunkHit() { return chunkRank > 0; }
    }

    /** 一组指标：Hit@1 / Hit@K / MRR */
    private record Metrics(String label, double hit1, double hitK, double mrr, int questions) {}

    /** 一种切分策略在整份语料上的表现：片段数 + 片段平均长度 + 混合通道的两级指标 */
    private record SplitterReport(String label, int chunkCount, double avgChunkChars, Metrics doc, Metrics chunk) {}

    /** 框架原生 naive RAG 基线（Ingestor → InMemoryEmbeddingStore → EmbeddingStoreContentRetriever）的成绩 */
    private record FrameworkBaseline(String label, int segmentCount, Metrics doc, Metrics chunk) {}

    @Test
    void hybridRetrievalAgainstSingleChannels() throws IOException {
        configurePipeline();
        List<Doc> docs = loadCorpus();
        List<Question> questions = loadQuestions();
        RetrievalIndex index = buildIndex(docs, splitter);
        assertTrue(index.chunkCount() > 0, "语料没有产生任何片段，检查切分参数与语料内容");

        // 标准答案一致性校验：标注的片段必须真的能在期望文档里找到，否则是标注写错而不是检索失败
        verifyGroundTruth(index, questions);

        Map<String, List<Outcome>> perMode = new LinkedHashMap<>();
        for (String mode : modes()) {
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

        // 切分策略对照：同一份语料、同一批问题、同一条混合通道，只换「怎么切」
        List<SplitterReport> splitterReports = new ArrayList<>();
        for (String name : List.of("self", "langchain4j")) {
            splitterReports.add(evaluateSplitter(name, docs, questions));
        }
        System.out.println("  —— 切分器对照（混合通道）——");
        splitterReports.forEach(report -> System.out.println("  " + report.label()
                + " | 片段=" + report.chunkCount() + "（均长 " + Math.round(report.avgChunkChars()) + " 字符）"
                + " | 文档级 Hit@1=" + pct(report.doc().hit1()) + " Hit@5=" + pct(report.doc().hitK())
                + " MRR=" + num(report.doc().mrr())
                + " | 片段级 Hit@5=" + pct(report.chunk().hitK())));

        // 框架原生 naive RAG 基线：Document → EmbeddingStoreIngestor → EmbeddingStoreContentRetriever（向量单路）
        FrameworkBaseline baseline = evaluateFrameworkNativePipeline(docs, questions);
        System.out.println("  —— 框架原生 naive RAG 基线 ——");
        System.out.println("  " + baseline.label() + " | 片段=" + baseline.segmentCount()
                + " | 文档级 Hit@1=" + pct(baseline.doc().hit1()) + " Hit@5=" + pct(baseline.doc().hitK())
                + " MRR=" + num(baseline.doc().mrr())
                + " | 片段级 Hit@5=" + pct(baseline.chunk().hitK()));

        // 题目风格分组：同一批事实、两种问法（原文措辞 / 口语改写），分组报数才看得出题目偏置有多少
        Map<String, Map<String, Metrics>> cohortDoc = new LinkedHashMap<>();
        Map<String, Map<String, Metrics>> cohortChunk = new LinkedHashMap<>();
        for (String cohort : List.of(COHORT_LITERAL, COHORT_PARAPHRASE)) {
            List<Integer> indexes = new ArrayList<>();
            for (int i = 0; i < questions.size(); i++) {
                if (cohort.equals(questions.get(i).cohort())) {
                    indexes.add(i);
                }
            }
            if (indexes.isEmpty()) {
                continue;
            }
            Map<String, Metrics> perModeDoc = new LinkedHashMap<>();
            Map<String, Metrics> perModeChunk = new LinkedHashMap<>();
            for (String mode : modes()) {
                List<Outcome> subset = indexes.stream().map(perMode.get(mode)::get).toList();
                perModeDoc.put(mode, metrics(label(mode) + " · " + cohort + " · 文档级", subset, Outcome::docRank));
                perModeChunk.put(mode, metrics(label(mode) + " · " + cohort + " · 片段级", subset, Outcome::chunkRank));
            }
            cohortDoc.put(cohort, perModeDoc);
            cohortChunk.put(cohort, perModeChunk);
        }
        System.out.println("  —— 题目风格分组（原文措辞 " + cohortSize(questions, COHORT_LITERAL)
                + " 题 / 口语改写 " + cohortSize(questions, COHORT_PARAPHRASE) + " 题）——");
        cohortDoc.forEach((cohort, modeMetrics) -> modeMetrics.forEach((mode, metrics) -> System.out.println(
                "  " + metrics + " | 片段级 Hit@" + TOP_K + "=" + pct(cohortChunk.get(cohort).get(mode).hitK()))));

        writeReport(index, questions, perMode, docLevel, chunkLevel, sweepDoc, sweepChunk,
                splitterReports, baseline, cohortDoc, cohortChunk);

        // 断言口径（离线词法向量）：混合至少要打赢它融合进来的向量通道，且不能让关键词通道塌方；
        // 「混合 ≥ 纯关键词」在这套口径下不成立 —— 哈希向量与 BM25 吃同一批 token，向量只是噪声来源，
        // 所以这里守的是绝对底线 + 两条相对下限，真实 embedding 模型的对照跑法见 README。
        Metrics hybridDoc = docLevel.get("hybrid");
        Metrics hybridChunk = chunkLevel.get("hybrid");
        // 断言分两层，避免「用一套口径的线去判另一套口径」：
        // ① 塌方线（任何口径、任何模型都要满足）：只防「检索整个坏掉」，阈值留足余量；
        // ② 回归线（只对默认离线口径 + 原文措辞组生效）：它们就是照着那 26 道题标定的，
        //    题库加了口语改写题之后，只有这一组还能和旧数字直接比。
        assertTrue(hybridDoc.hitK() >= 0.70d, "混合的文档级 Hit@5 低于塌方线 0.70");
        assertTrue(hybridChunk.hitK() >= 0.55d, "混合的片段级 Hit@5 低于塌方线 0.55");

        if (HashingEmbeddingModel.MODEL_NAME.equals(embeddingModelId)) {
            Metrics literalDoc = cohortDoc.get(COHORT_LITERAL).get("hybrid");
            Metrics literalChunk = cohortChunk.get(COHORT_LITERAL).get("hybrid");
            assertTrue(literalDoc.hitK() >= docLevel.get("vector").hitK(), "原文措辞组：混合的文档级 Hit@5 不应低于纯向量");
            assertTrue(literalChunk.hitK() >= chunkLevel.get("vector").hitK(), "原文措辞组：混合的片段级 Hit@5 不应低于纯向量");
            assertTrue(literalDoc.hitK() >= 0.80d, "原文措辞组：混合的文档级 Hit@5 低于底线 0.80");
            assertTrue(literalChunk.hitK() >= 0.60d, "原文措辞组：混合的片段级 Hit@5 低于底线 0.60");
            assertTrue(literalDoc.mrr() > 0.80d, "原文措辞组：混合的文档级 MRR 低于底线 0.80");
        }

        // 口语改写组只守塌方线：它是用来暴露瓶颈的探针，不是拿来达标的 KPI
        Metrics paraphraseDoc = cohortDoc.get(COHORT_PARAPHRASE).get("hybrid");
        assertTrue(paraphraseDoc.hitK() >= 0.50d,
                "口语改写组的文档级 Hit@5 低于 0.50 —— 检索对「换个说法」几乎失效了，先查切分与标注");

        // 框架原生基线的断言只守「跑得通、不是废的」：它是参照物，不是要达标的 KPI。
        // 数字高低如实进报告 —— 参照物要是也能达标，那说明该考虑换掉自研；达不到，正好是自研的理由。
        assertTrue(baseline.doc().hitK() >= 0.50d,
                "框架原生链路的文档级 Hit@5 低于 0.50，多半是元数据/接线断了而不是检索差");
        assertTrue(baseline.chunk().hitK() >= 0.40d, "框架原生链路的片段级 Hit@5 低于 0.40");

        // RRF 同样只守「不是废的」：它是被检验的候选策略，赢不赢 BM25 是结论（写进报告），不是断言。
        assertTrue(docLevel.get("rrf").hitK() >= 0.80d, "RRF 的文档级 Hit@5 低于底线 0.80，融合实现可能有问题");
        assertTrue(chunkLevel.get("rrf").hitK() >= 0.60d, "RRF 的片段级 Hit@5 低于底线 0.60");

        // 精排只在配了模型时才跑：同样只守「不是废的」，通过/未通过写进报告（D-21 的验收判定）
        if (rerankAvailable) {
            assertTrue(docLevel.get("rrf+rerank").hitK() >= 0.80d,
                    "RRF+精排的文档级 Hit@5 低于底线 0.80 —— 多半是精排分与召回分的混合接错了");
            assertTrue(docLevel.get("hybrid+rerank").hitK() >= 0.80d, "加权+精排的文档级 Hit@5 低于底线 0.80");
        }

        // 题库必须真的有口语改写题 —— 否则「分组报数」是假的分组，D-17 遗留 ① 会悄悄回到原样
        long paraphrase = cohortSize(questions, COHORT_PARAPHRASE);
        assertTrue(paraphrase >= 10,
                "口语改写题少于 10 道（当前 " + paraphrase + "），题目风格分组失去意义，请先补题库");
    }

    /**
     * 教科书式 naive RAG 基线：{@code Document} → {@code EmbeddingStoreIngestor}（切分 → 向量化 → 落库）
     * → {@code EmbeddingStoreContentRetriever}（向量单路 top-k）。
     * <p>
     * 它存在的意义是当参照物：自研那套（混合召回 + 引用拼装 + 降级）值不值，得先有一条
     * 「公认的默认答案」在同一份语料上量一遍。切分用框架自带的 {@code recursive}（框架原生链路不会
     * 知道我们的 Markdown 标题），向量模型与自研链路共用同一个离线哈希模型 —— 差别只在链路本身。
     */
    private FrameworkBaseline evaluateFrameworkNativePipeline(List<Doc> docs, List<Question> questions) {
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(DocumentSplitters.recursive(CHUNK_MAX_CHARS, CHUNK_OVERLAP_CHARS))
                .embeddingModel(embedder)
                .embeddingStore(store)
                .build();
        List<Document> documents = new ArrayList<>(docs.size());
        for (Doc doc : docs) {
            documents.add(Document.from(doc.text(), Metadata.from(DOC_ID_KEY, doc.id())));
        }
        ingestor.ingest(documents);

        ContentRetriever retriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(store)
                .embeddingModel(embedder)
                .maxResults(TOP_K)
                .build();

        List<Outcome> outcomes = new ArrayList<>(questions.size());
        for (Question question : questions) {
            outcomes.add(evaluateFrameworkHit(question, retriever.retrieve(Query.from(question.text()))));
        }
        String label = "框架原生 naive RAG（Ingestor + ContentRetriever，向量单路）";
        return new FrameworkBaseline(label, store.size(), metrics(label + " · 文档级", outcomes, Outcome::docRank),
                metrics(label + " · 片段级", outcomes, Outcome::chunkRank));
    }

    /** 框架链路的判定口径与自研一致：文档级看元数据里的来源 id，片段级看片段文本是否含标准答案 */
    private static Outcome evaluateFrameworkHit(Question question, List<Content> contents) {
        int docRank = 0;
        int chunkRank = 0;
        for (int i = 0; i < contents.size(); i++) {
            TextSegment segment = contents.get(i).textSegment();
            String docId = segment.metadata().getString(DOC_ID_KEY);
            if (docRank == 0 && docId != null && question.expectedDocs().contains(docId)) {
                docRank = i + 1;
            }
            if (chunkRank == 0 && segment.text().contains(question.expectedContains())) {
                chunkRank = i + 1;
            }
        }
        return new Outcome(docRank, chunkRank);
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
                : index.searchVector(embed(query), embeddingModelId, CANDIDATES);
        List<KnowledgeVectorIndex.Scored> keywordHits = "vector".equals(mode)
                ? List.of()
                : index.searchKeyword(query, CANDIDATES);

        List<Ranked> ranked = new ArrayList<>();
        if ("vector".equals(mode)) {
            vectorHits.forEach(hit -> ranked.add(new Ranked(hit.payload(), hit.score())));
        } else if ("keyword".equals(mode)) {
            keywordHits.forEach(hit -> ranked.add(new Ranked(hit.payload(), hit.score())));
        } else if ("rrf".equals(mode)) {
            HybridFusion.fuseRrf(vectorHits, keywordHits, RRF_K)
                    .forEach(fused -> ranked.add(new Ranked(fused.payload(), fused.score())));
        } else if ("hybrid+rerank".equals(mode) || "rrf+rerank".equals(mode)) {
            List<HybridFusion.Fused<KnowledgeVectorIndex.Entry>> fused = "rrf+rerank".equals(mode)
                    ? HybridFusion.fuseRrf(vectorHits, keywordHits, RRF_K)
                    : HybridFusion.fuse(vectorHits, keywordHits, vectorWeight);
            rerank(query, fused);
            fused.forEach(hit -> ranked.add(new Ranked(hit.payload(), hit.score())));
        } else {
            HybridFusion.fuse(vectorHits, keywordHits, vectorWeight)
                    .forEach(fused -> ranked.add(new Ranked(fused.payload(), fused.score())));
        }
        ranked.sort(Comparator.comparingDouble(Ranked::score).reversed());
        return ranked.stream().limit(TOP_K).toList();
    }

    /**
     * 精排：候选文本与生产同口径（有标题路径就拼上 heading 再送模型），混合公式也是生产那一份
     * （{@link HybridFusion#applyRerankScores}），所以这里量到的就是线上会发生的重排。
     */
    private void rerank(String query, List<HybridFusion.Fused<KnowledgeVectorIndex.Entry>> fused) {
        if (!rerankAvailable || fused.isEmpty()) {
            return;
        }
        List<String> texts = new ArrayList<>(fused.size());
        for (HybridFusion.Fused<KnowledgeVectorIndex.Entry> hit : fused) {
            String heading = hit.payload().heading();
            texts.add(heading == null || heading.isBlank()
                    ? hit.payload().content()
                    : heading + "\n" + hit.payload().content());
        }
        List<Double> scores = reranker.score(query, texts);
        HybridFusion.applyRerankScores(fused, scores, HybridFusion.DEFAULT_RERANK_WEIGHT);
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
            case "rrf" -> "融合 RRF (k=" + RRF_K + ")";
            case "hybrid+rerank" -> "加权融合 + 精排";
            case "rrf+rerank" -> "RRF + 精排";
            default -> "混合 0.65 : 0.35";
        };
    }

    /**
     * 一种切分策略的对照评测：用同一套向量编码、同一批问题、同一条混合通道，只换「怎么切」。
     * <p>
     * 这里会再跑一遍标注自检 —— 换了切分器之后，标注的字面串仍必须落在某个片段里。
     * 如果切分把答案切丢了，那是切分的问题（真问题），不能记到检索头上。
     */
    private SplitterReport evaluateSplitter(String name, List<Doc> docs, List<Question> questions) {
        TextSplitter candidate = splitter(name);
        RetrievalIndex candidateIndex = buildIndex(docs, candidate);
        verifyGroundTruth(candidateIndex, questions);

        List<TextChunker.Chunk> allChunks = new ArrayList<>();
        for (Doc doc : docs) {
            allChunks.addAll(candidate.split(doc.text()));
        }
        double avgChunkChars = allChunks.isEmpty() ? 0d
                : allChunks.stream().mapToInt(chunk -> chunk.text().length()).average().orElse(0d);

        List<Outcome> outcomes = new ArrayList<>(questions.size());
        for (Question question : questions) {
            outcomes.add(evaluate(question, search("hybrid", question.text(), candidateIndex)));
        }
        String label = "langchain4j".equalsIgnoreCase(name)
                ? "LangChain4j DocumentSplitters.recursive"
                : "自研标题感知切分";
        return new SplitterReport(label, candidateIndex.chunkCount(), avgChunkChars,
                metrics(label + " · 文档级", outcomes, Outcome::docRank),
                metrics(label + " · 片段级", outcomes, Outcome::chunkRank));
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
            // 第 5 列（可选）是题目风格分组：literal（默认）或 paraphrase
            String cohort = parts.length > 4 && !parts[4].isBlank() ? parts[4].trim() : COHORT_LITERAL;
            questions.add(new Question(parts[0].trim(), parts[1].trim(), expected, parts[3].trim(), cohort));
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

    private RetrievalIndex buildIndex(List<Doc> docs, TextSplitter textSplitter) {
        List<KnowledgeChunk> chunks = new ArrayList<>();
        Map<Long, KnowledgeDocument> documentsById = new LinkedHashMap<>();
        long documentId = 1L;
        for (Doc doc : docs) {
            KnowledgeDocument document = new KnowledgeDocument(KnowledgeDocument.SOURCE_RESOURCE,
                    doc.id(), doc.title(), "eval-hash-" + documentId,
                    embeddingModelId, embeddingDimension);
            document.setId(documentId);
            documentsById.put(documentId, document);
            documentIds.put(doc.id(), documentId);
            List<TextChunker.Chunk> pieces = textSplitter.split(doc.text());
            List<float[]> vectors = embedBatch(pieces.stream().map(TextChunker.Chunk::text).toList());
            for (int i = 0; i < pieces.size(); i++) {
                TextChunker.Chunk piece = pieces.get(i);
                chunks.add(new KnowledgeChunk(documentId, piece.index(), piece.heading(), piece.text(),
                        EmbeddingCodec.encode(vectors.get(i)), vectors.get(i).length, embeddingModelId));
            }
            documentId++;
        }
        KnowledgeVectorIndex index = new KnowledgeVectorIndex(true);
        index.rebuild(chunks, documentsById);
        return new InMemoryRetrievalIndex(index);
    }

    /**
     * 批量向量化 —— 与生产同口径：{@code embedAll} 分批（DashScope 单次有上限），
     * 每条向量做 L2 归一化（生产在 {@code KnowledgeIndexService} 与 {@code KnowledgeRetriever} 里都这么做）。
     * 离线哈希向量本身就是归一化的，所以这条路径对默认口径是零影响。
     */
    private List<float[]> embedBatch(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += EMBED_BATCH_SIZE) {
            List<String> batch = texts.subList(i, Math.min(texts.size(), i + EMBED_BATCH_SIZE));
            List<Embedding> embeddings = embedder.embedAll(batch.stream().map(TextSegment::from).toList()).content();
            if (embeddings == null || embeddings.size() != batch.size()) {
                throw new IllegalStateException("向量模型返回条数不匹配: 期望 " + batch.size());
            }
            for (Embedding embedding : embeddings) {
                float[] vector = embedding.vector();
                EmbeddingCodec.normalize(vector);
                vectors.add(vector);
            }
        }
        return vectors;
    }

    private float[] embed(String text) {
        float[] vector = embedder.embed(text).content().vector();
        EmbeddingCodec.normalize(vector);
        return vector;
    }

    /**
     * 按 {@code rag.eval.model} 装配向量模型：默认离线词法哈希；{@code dashscope} 时用真实语义向量
     * （key 从环境变量取，与生产 {@code RagConfiguration} 同一来源：{@code RAG_EMBEDDING_API_KEY}
     * 缺省复用 {@code LLM_STT_API_KEY}）。没有 key 时直接跳过，而不是跑出一份假数据。
     */
    private void configurePipeline() {
        String apiKey = firstNonBlank(System.getenv("RAG_EMBEDDING_API_KEY"), System.getenv("LLM_STT_API_KEY"));
        if ("dashscope".equalsIgnoreCase(EVAL_MODEL)) {
            if (apiKey == null) {
                Assumptions.abort("rag.eval.model=dashscope 需要环境变量 RAG_EMBEDDING_API_KEY（或 LLM_STT_API_KEY）");
            }
            String modelName = System.getProperty("rag.eval.embeddingModel", "text-embedding-v4");
            int dimension = Integer.parseInt(System.getProperty("rag.eval.dimension", "1024"));
            this.embedder = QwenEmbeddingModel.builder()
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .dimension(dimension)
                    .build();
            this.embeddingModelId = modelName;
            this.embeddingDimension = dimension;
            System.out.println("  向量模型: DashScope " + modelName + "（dimension=" + dimension + "）");
        }
        configureReranker(apiKey);
    }

    /**
     * 精排按生产口径装配：直接调 {@code RagConfiguration#ragReranker}，让「评测用的精排器」
     * 就是「线上用的那个」（同一份 DashScope 装配、同一份失败降级为 noop 的语义）。
     * <p>
     * 装完先探一次（一次真实调用）：拿不到分数就说明精排没配起来（例如缺 key 或 SDK 版本不支持），
     * 那就退回不跑「融合 + 精排」两行，并在报告里写明「未跑」，而不是拿一份假数据充数。
     */
    private void configureReranker(String apiKey) {
        if (!"dashscope".equalsIgnoreCase(EVAL_RERANK)) {
            return;
        }
        if (apiKey == null) {
            Assumptions.abort("rag.eval.rerank=dashscope 需要环境变量 RAG_EMBEDDING_API_KEY（或 LLM_STT_API_KEY）");
        }
        RagProperties props = new RagProperties();
        ReflectionTestUtils.setField(props, "rerankEnabled", true);
        ReflectionTestUtils.setField(props, "embeddingApiKey", apiKey);
        ReflectionTestUtils.setField(props, "rerankModel",
                System.getProperty("rag.eval.rerankModel", "gte-rerank-v2"));
        this.reranker = new RagConfiguration().ragReranker(props);
        List<Double> probe;
        try {
            probe = reranker.score("什么是 RAG", List.of("RAG 是检索增强生成：先检索再让模型回答。"));
        } catch (Exception e) {
            probe = null;
        }
        this.rerankAvailable = probe != null && probe.size() == 1 && probe.get(0) != null;
        System.out.println(rerankAvailable
                ? "  精排模型: DashScope " + props.getRerankModel() + "（已探活）"
                : "  精排不可用，本轮跳过「融合 + 精排」对照");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /** 切分策略按名字取实现：{@code self} = 自研标题感知切分，{@code langchain4j} = 框架递归拆分 */
    private static TextSplitter splitter(String name) {
        RagProperties props = new RagProperties();
        ReflectionTestUtils.setField(props, "chunkMaxChars", CHUNK_MAX_CHARS);
        ReflectionTestUtils.setField(props, "chunkOverlapChars", CHUNK_OVERLAP_CHARS);
        return "langchain4j".equalsIgnoreCase(name) ? new Langchain4jTextSplitter(props) : new TextChunker(props);
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
                             Map<Double, Metrics> sweepDoc, Map<Double, Metrics> sweepChunk,
                             List<SplitterReport> splitterReports, FrameworkBaseline baseline,
                             Map<String, Map<String, Metrics>> cohortDoc,
                             Map<String, Map<String, Metrics>> cohortChunk) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# RAG 检索评测：混合 vs 单路\n\n");
        sb.append("由 `RagEvaluationTest` 生成。向量模型：`").append(embeddingModelId).append("`（")
                .append(embeddingDimension).append(" 维），三个通道吃同一份语料、同一批问题。\n\n");
        sb.append("- 语料 ").append(index.chunkCount()).append(" 个片段（公开文档，见 `sources.tsv`）\n");
        sb.append("- 题目 ").append(questions.size()).append(" 条，人工标注「期望文档 + 答案里的字面串」\n");
        sb.append("- 切分与生产一致：maxChars=").append(CHUNK_MAX_CHARS).append(" / overlap=").append(CHUNK_OVERLAP_CHARS)
                .append("（主表用自研标题感知切分，对照见「切分器对照」一节）；候选 = topK × 3，融合权重 ")
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

        sb.append("\n## 切分器对照（同一份语料与题目，走混合通道）\n\n");
        sb.append("| 切分策略 | 片段数 | 片段均长 | 文档级 Hit@1 | 文档级 Hit@").append(TOP_K)
                .append(" | 文档级 MRR | 片段级 Hit@1 | 片段级 Hit@").append(TOP_K).append(" | 片段级 MRR |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");
        for (SplitterReport report : splitterReports) {
            sb.append("| ").append(report.label())
                    .append(" | ").append(report.chunkCount())
                    .append(" | ").append(Math.round(report.avgChunkChars()))
                    .append(" | ").append(pct(report.doc().hit1())).append(" | ").append(pct(report.doc().hitK()))
                    .append(" | ").append(num(report.doc().mrr()))
                    .append(" | ").append(pct(report.chunk().hit1())).append(" | ").append(pct(report.chunk().hitK()))
                    .append(" | ").append(num(report.chunk().mrr())).append(" |\n");
        }
        sb.append("- 自研切分器先按 Markdown 标题切小节、再降级拆分，片段带标题路径（引用能定位到小节）；\n")
                .append("  LangChain4j 的 `DocumentSplitters.recursive` 只看段落/句子/字符长度，片段没有标题路径。\n")
                .append("- 两者产出同一个 DTO，`rag.splitter=self|langchain4j` 切换，检索与融合逻辑一行都不用改。\n");

        sb.append("\n## 框架原生 naive RAG 基线（LangChain4j Ingestor + ContentRetriever）\n\n");
        sb.append("| 链路 | 片段数 | 文档级 Hit@1 | 文档级 Hit@").append(TOP_K)
                .append(" | 文档级 MRR | 片段级 Hit@1 | 片段级 Hit@").append(TOP_K).append(" | 片段级 MRR |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");
        sb.append("| ").append(baseline.label()).append(" | ").append(baseline.segmentCount())
                .append(" | ").append(pct(baseline.doc().hit1())).append(" | ").append(pct(baseline.doc().hitK()))
                .append(" | ").append(num(baseline.doc().mrr()))
                .append(" | ").append(pct(baseline.chunk().hit1())).append(" | ").append(pct(baseline.chunk().hitK()))
                .append(" | ").append(num(baseline.chunk().mrr())).append(" |\n");
        Metrics inHouseDoc = docLevel.get("hybrid");
        Metrics inHouseChunk = chunkLevel.get("hybrid");
        sb.append("| 自研链路（混合召回 + 引用拼装） | ").append(index.chunkCount())
                .append(" | ").append(pct(inHouseDoc.hit1())).append(" | ").append(pct(inHouseDoc.hitK()))
                .append(" | ").append(num(inHouseDoc.mrr()))
                .append(" | ").append(pct(inHouseChunk.hit1())).append(" | ").append(pct(inHouseChunk.hitK()))
                .append(" | ").append(num(inHouseChunk.mrr())).append(" |\n");
        sb.append("- 两条链路吃同一份语料、同一批 ").append(questions.size()).append(" 题、同一个离线向量模型（`")
                .append(HashingEmbeddingModel.MODEL_NAME).append("`），差别只在链路本身：\n")
                .append("  框架那条是 `Document` → `EmbeddingStoreIngestor`（切分→向量化→落库）→ ")
                .append("`EmbeddingStoreContentRetriever`（向量单路 top-k）；自研那条多了关键词通道与片段级引用路径。\n");
        sb.append("- 读法：框架链路没有关键词通道，在词面型题库上天然吃亏；它也不提供「哪一份文档的哪一节」这种引用定位。\n")
                .append("  这也是生产写入路径没有换成 `EmbeddingStoreIngestor` 的原因 —— 它以自动生成的点 id 落库，\n")
                .append("  而我们需要 `文档#片段` 派生的稳定点 id（重复灌库是覆盖不是新增），且 MySQL 才是权威数据源。\n");

        sb.append("\n## 题目风格分组：原文措辞 vs 口语改写\n\n");
        sb.append("同一批事实、两种问法。分组报数的目的：把「检索本身好不好」与「题目复用了文档措辞」分开 ——\n")
                .append("只报一组数字时，结论有可能是题目风格选出来的。\n");
        for (Map.Entry<String, Map<String, Metrics>> cohortEntry : cohortDoc.entrySet()) {
            sb.append("\n### ").append(cohortLabel(cohortEntry.getKey()))
                    .append("（").append(cohortEntry.getValue().values().iterator().next().questions()).append(" 题）\n\n");
            sb.append("| 通道 | 文档级 Hit@1 | 文档级 Hit@").append(TOP_K)
                    .append(" | 文档级 MRR | 片段级 Hit@1 | 片段级 Hit@").append(TOP_K).append(" | 片段级 MRR |\n");
            sb.append("|---|---|---|---|---|---|---|\n");
            for (String mode : modes()) {
                Metrics doc = cohortEntry.getValue().get(mode);
                Metrics chunk = cohortChunk.get(cohortEntry.getKey()).get(mode);
                sb.append("| ").append(label(mode))
                        .append(" | ").append(pct(doc.hit1())).append(" | ").append(pct(doc.hitK()))
                        .append(" | ").append(num(doc.mrr()))
                        .append(" | ").append(pct(chunk.hit1())).append(" | ").append(pct(chunk.hitK()))
                        .append(" | ").append(num(chunk.mrr())).append(" |\n");
            }
        }
        if (cohortDoc.containsKey(COHORT_LITERAL) && cohortDoc.containsKey(COHORT_PARAPHRASE)) {
            sb.append("\n口语改写组相对原文措辞组的变化（负号 = 改写后变差）：\n\n");
            sb.append("| 通道 | 文档级 Hit@1 | 文档级 MRR | 片段级 Hit@1 | 片段级 MRR |\n|---|---|---|---|---|\n");
            for (String mode : modes()) {
                Metrics litDoc = cohortDoc.get(COHORT_LITERAL).get(mode);
                Metrics parDoc = cohortDoc.get(COHORT_PARAPHRASE).get(mode);
                Metrics litChunk = cohortChunk.get(COHORT_LITERAL).get(mode);
                Metrics parChunk = cohortChunk.get(COHORT_PARAPHRASE).get(mode);
                sb.append("| ").append(label(mode))
                        .append(" | ").append(delta(parDoc.hit1() - litDoc.hit1()))
                        .append(" | ").append(delta(parDoc.mrr() - litDoc.mrr()))
                        .append(" | ").append(delta(parChunk.hit1() - litChunk.hit1()))
                        .append(" | ").append(delta(parChunk.mrr() - litChunk.mrr())).append(" |\n");
            }
            sb.append("- 读法：如果改写组三条通道一起掉，说明瓶颈在「词面之外的理解」而不在融合策略；\n")
                    .append("  如果只有关键词通道掉、向量通道稳住，那才是向量通道的价值被量出来。\n");
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
        sb.append("\n## 结论（").append(embeddingModelId).append(" 口径）\n\n");
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
        Metrics keywordDoc = docLevel.get("keyword");
        Metrics rrfDoc = docLevel.get("rrf");
        boolean rrfAccepted = rrfDoc.hit1() > keywordDoc.hit1() && rrfDoc.mrr() > keywordDoc.mrr();
        sb.append("- **融合策略验收（D-20 标准：文档级 Hit@1 与 MRR 同时超过纯 BM25）**：")
                .append(rrfAccepted ? "**通过**" : "**未通过**")
                .append(" —— RRF ").append(pct(rrfDoc.hit1())).append(" / ").append(num(rrfDoc.mrr()))
                .append(" vs BM25 ").append(pct(keywordDoc.hit1())).append(" / ").append(num(keywordDoc.mrr()))
                .append("（片段级：RRF ").append(num(chunkLevel.get("rrf").mrr()))
                .append(" vs BM25 ").append(num(chunkLevel.get("keyword").mrr())).append("）\n");
        if (!rrfAccepted) {
            sb.append("  → 名次融合赢召回、输排头：按 D-21 的结论，不换默认融合，改走「RRF 召回 + 精排」路线。\n");
        }
        if (rerankAvailable && docLevel.containsKey("rrf+rerank")) {
            Metrics rrfRerankDoc = docLevel.get("rrf+rerank");
            Metrics rrfRerankChunk = chunkLevel.get("rrf+rerank");
            boolean rerankAccepted = rrfRerankDoc.hit1() > keywordDoc.hit1()
                    && rrfRerankDoc.mrr() > keywordDoc.mrr()
                    && rrfRerankChunk.hitK() >= chunkLevel.get("rrf").hitK();
            sb.append("- **精排验收（D-21 标准：重排后文档级 Hit@1 与 MRR 同时超过纯 BM25，")
                    .append("且片段级 Hit@5 不低于 RRF）**：").append(rerankAccepted ? "**通过**" : "**未通过**")
                    .append(" —— RRF+精排 ").append(pct(rrfRerankDoc.hit1())).append(" / ").append(num(rrfRerankDoc.mrr()))
                    .append("（片段级 Hit@").append(TOP_K).append(" ").append(pct(rrfRerankChunk.hitK())).append("）")
                    .append(" vs BM25 ").append(pct(keywordDoc.hit1())).append(" / ").append(num(keywordDoc.mrr()))
                    .append("、RRF ").append(pct(chunkLevel.get("rrf").hitK())).append("\n");
            sb.append("- 精排前后（文档级 Hit@1 / MRR）：加权 ")
                    .append(pct(docLevel.get("hybrid").hit1())).append(" / ").append(num(docLevel.get("hybrid").mrr()))
                    .append(" → ").append(pct(docLevel.get("hybrid+rerank").hit1())).append(" / ")
                    .append(num(docLevel.get("hybrid+rerank").mrr()))
                    .append("；RRF ").append(pct(rrfDoc.hit1())).append(" / ").append(num(rrfDoc.mrr()))
                    .append(" → ").append(pct(rrfRerankDoc.hit1())).append(" / ").append(num(rrfRerankDoc.mrr()))
                    .append("。\n");
        } else {
            sb.append("- 精排对照本轮未跑（`-Drag.eval.rerank=dashscope` 需要 DashScope key）。\n");
        }
        sb.append("- 权重扫描：文档级 Hit@").append(TOP_K)
                .append(" 在 w ∈ [0.20, 0.80] 上最高出现在 w=")
                .append(fmt(bestWeight.getKey())).append("（").append(pct(bestWeight.getValue().hitK())).append("）")
                .append("，最好的一档 MRR=")
                .append(num(sweepDoc.values().stream().mapToDouble(Metrics::mrr).max().orElse(0d)));
        if (HashingEmbeddingModel.MODEL_NAME.equals(embeddingModelId)) {
            sb.append(" —— 离线哈希向量与 BM25 的候选高度重合，w 只影响 MRR 与 Hit@1，而这套口径下它无从体现。\n");
        } else {
            sb.append(" —— 真实向量下权重才真正开始起作用（对片段级排序影响明显），\n")
                    .append("  但如果每条 w 都赢不过纯关键词，那就说明该换的是融合策略而不是权重（见 D-20）。\n");
        }
        if (HashingEmbeddingModel.MODEL_NAME.equals(embeddingModelId)) {
            sb.append("- 口径提醒：`").append(HashingEmbeddingModel.MODEL_NAME)
                    .append("` 与 BM25 吃的是同一批 token，向量通道几乎只额外带来哈希噪声，所以这套离线口径天然偏向关键词通道；\n")
                    .append("  要验证生产融合权重（0.65 : 0.35）需要换成真实 embedding 模型重跑：\n")
                    .append("  `mvn -B test -Dtest=RagEvaluationTest -Drag.eval.model=dashscope ")
                    .append("-Drag.eval.report=target/bench/rag-eval-dashscope.md`（需 `RAG_EMBEDDING_API_KEY`）。\n");
        } else {
            sb.append("- 口径：这次用的是**真实语义向量**（`").append(embeddingModelId)
                    .append("`，").append(embeddingDimension).append(" 维），向量通道不再被词面重合度限制；\n")
                    .append("  两份报告对照读（`docs/bench/rag-eval.md` 离线口径 vs 本文件）才能说清「换模型」值多少。\n");
        }

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

    /** 带正负号的差值（报告里用来表示「改写后变化了多少」） */
    private static String delta(double value) {
        return String.format(Locale.ROOT, "%+.3f", value);
    }

    private static String cohortLabel(String cohort) {
        return switch (cohort) {
            case COHORT_LITERAL -> "原文措辞组";
            case COHORT_PARAPHRASE -> "口语改写组";
            default -> cohort;
        };
    }

    private static long cohortSize(List<Question> questions, String cohort) {
        return questions.stream().filter(question -> cohort.equals(question.cohort())).count();
    }

    private static String pct(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String num(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
