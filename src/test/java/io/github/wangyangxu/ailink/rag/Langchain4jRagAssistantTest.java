package io.github.wangyangxu.ailink.rag;

import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.github.wangyangxu.ailink.config.RagConfiguration;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端问答演示 —— <b>AiServices + ContentRetriever 的完整体验</b>（P1 的收尾）。
 * <p>
 * 与 {@code RagEvaluationTest}（只量检索、不调对话模型）分工不同：这里真的让模型读检索结果回答，
 * 并把「问题 → 检索到哪些片段 → 回答」全过程落成一份 transcript（{@code target/bench/rag-assistant-demo.md}），
 * 用来回答一个面试问题：<i>你真的把 RAG 串到端了吗？</i>
 * <p>
 * 两条链路对同一个问题各跑一遍：
 * <ul>
 *   <li><b>框架原生</b>：{@code Document → EmbeddingStoreIngestor → InMemoryEmbeddingStore →
 *       EmbeddingStoreContentRetriever}（教科书式 naive RAG）；</li>
 *   <li><b>自研检索接入框架</b>：把项目的混合检索（向量 ∥ BM25 + 精排）适配成 {@link ContentRetriever}，
 *       框架只负责拼 prompt 与调模型 —— 这是「自研检索能不能被框架直接消费」的验证。</li>
 * </ul>
 * 需要 DashScope key（{@code RAG_EMBEDDING_API_KEY} 或 {@code LLM_STT_API_KEY}），CI 里自动跳过。
 */
class Langchain4jRagAssistantTest {

    private static final Path CORPUS_DIR = Path.of("src", "test", "resources", "rag-eval");
    private static final Path REPORT_FILE = Path.of("target", "bench", "rag-assistant-demo.md");
    private static final int TOP_K = 4;
    private static final int CANDIDATES = TOP_K * 3;
    private static final double VECTOR_WEIGHT = 0.65d;
    private static final int CHUNK_MAX_CHARS = 800;
    private static final int CHUNK_OVERLAP_CHARS = 120;
    private static final String DOC_ID_KEY = "doc_id";

    /** 演示问题：三个主题各一题，答案在语料里有明确出处（沿用评测题库里的三道原文措辞题） */
    private record DemoCase(String question, String expectedDoc, String expectedContains) {}

    private static final List<DemoCase> CASES = List.of(
            new DemoCase("In Spring's declarative transactions, are checked exceptions rolled back by default?",
                    "spring-boot/spring-boot-tx-rollback.md",
                    "Checked exceptions that are thrown from a transactional method"),
            new DemoCase("所谓“敌国条款”到底指哪几条，规定了什么内容？",
                    "enemy-state-clause/gmw-enemy-clause-explained.md",
                    "第53条、第77条及第107条"),
            new DemoCase("2022 年泄露的《GTA6》片段有多少个，最后是在哪里被放出来的？",
                    "gta6-leak/gamersky-breach-trial-details.md",
                    "90个《GTA6》开发片段在GTAForums上泄露"));

    /** AI Service：只要一个方法，检索、拼 prompt、调模型都由框架串起来 */
    interface KnowledgeAssistant {
        @SystemMessage("""
                你是知识库助手。只依据检索到的资料回答，用与提问相同的语言，先给结论再给要点，不要大段照抄原文。
                资料里没有答案时直接说「资料里没有」，不要编。
                不要输出「来源」列表：出处由系统根据检索结果统一附加，模型只负责答案正文。
                """)
        String ask(@UserMessage String question);
    }

    private record Doc(String id, String title, String text) {}

    @Test
    void answersWithCitationsThroughAiServices() throws IOException {
        String apiKey = firstNonBlank(System.getenv("RAG_EMBEDDING_API_KEY"), System.getenv("LLM_STT_API_KEY"));
        Assumptions.assumeTrue(apiKey != null,
                "端到端演示需要 DashScope key（RAG_EMBEDDING_API_KEY 或 LLM_STT_API_KEY），CI 里自动跳过");

        String embeddingModelName = System.getProperty("rag.demo.embeddingModel", "text-embedding-v4");
        int dimension = Integer.parseInt(System.getProperty("rag.demo.dimension", "1024"));
        String chatModelName = System.getProperty("rag.demo.chatModel", "qwen-plus");
        EmbeddingModel embedder = QwenEmbeddingModel.builder()
                .apiKey(apiKey).modelName(embeddingModelName).dimension(dimension).build();
        ChatModel chatModel = QwenChatModel.builder()
                .apiKey(apiKey).modelName(chatModelName).temperature(0.1f).build();

        List<Doc> docs = loadCorpus();
        System.out.println("  语料 " + docs.size() + " 篇；对话模型 " + chatModelName + "；向量模型 " + embeddingModelName);

        // 路径 A：框架原生（Ingestor 负责切分 → 向量化 → 落库）
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        EmbeddingStoreIngestor.builder()
                .documentSplitter(DocumentSplitters.recursive(CHUNK_MAX_CHARS, CHUNK_OVERLAP_CHARS))
                .embeddingModel(embedder)
                .embeddingStore(store)
                .build()
                .ingest(docs.stream().map(doc -> Document.from(doc.text(), Metadata.from(DOC_ID_KEY, doc.id()))).toList());
        ContentRetriever frameworkRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(store).embeddingModel(embedder).maxResults(TOP_K).build();

        // 路径 B：自研检索（混合 + 精排）适配成 ContentRetriever，框架只做 prompt 与调用
        RetrievalIndex index = buildIndex(docs, embedder, embeddingModelName, dimension);
        Reranker reranker = new RagConfiguration().ragReranker(demoRagProperties(apiKey));
        ContentRetriever selfRetriever = query -> selfSearch(query.text(), index, embedder, embeddingModelName, reranker)
                .stream()
                .map(hit -> Content.from(TextSegment.from(hit.payload().content(),
                        Metadata.from(DOC_ID_KEY, hit.payload().sourcePath()))))
                .toList();

        KnowledgeAssistant frameworkAssistant = AiServices.builder(KnowledgeAssistant.class)
                .chatModel(chatModel).contentRetriever(frameworkRetriever).build();
        KnowledgeAssistant selfAssistant = AiServices.builder(KnowledgeAssistant.class)
                .chatModel(chatModel).contentRetriever(selfRetriever).build();

        StringBuilder report = new StringBuilder();
        int chunkHits = 0;
        report.append("# 端到端问答演示（AiServices + ContentRetriever）\n\n")
                .append("由 `Langchain4jRagAssistantTest` 生成。对话模型 `").append(chatModelName)
                .append("`、向量模型 `").append(embeddingModelName).append("`，语料为 `rag-eval` 的 ")
                .append(docs.size()).append(" 篇公开文档。\n\n")
                .append("两条链路对同一个问题各答一遍：**框架原生**（Ingestor → InMemoryEmbeddingStore → ")
                .append("EmbeddingStoreContentRetriever）与**自研检索接入框架**（混合召回 + 精排 → 适配成 ContentRetriever）。\n");

        for (DemoCase demoCase : CASES) {
            List<Content> frameworkHits = frameworkRetriever.retrieve(dev.langchain4j.rag.query.Query.from(demoCase.question()));
            List<HybridFusion.Fused<KnowledgeVectorIndex.Entry>> selfHits =
                    selfSearch(demoCase.question(), index, embedder, embeddingModelName, reranker);

            String frameworkAnswer = frameworkAssistant.ask(demoCase.question());
            String selfAnswer = selfAssistant.ask(demoCase.question());

            // 断言只守「端到端演示成立」的最低条件：期望文档被召回 + 两条回答都非空。
            // 检索本身的严格口径（片段级 rank、Hit@K、cohort 分组）在 RagEvaluationTest 里，
            // 这里用生产同款参数（top-4 / 候选 12），所以它顺带是个「生产设置下答案片段进没进前 4」的探针 ——
            // 结果如实记进 transcript，而不是拿两套阈值互相判红。
            boolean docHit = selfHits.stream().anyMatch(hit -> demoCase.expectedDoc().equals(hit.payload().sourcePath()));
            boolean chunkHit = selfHits.stream().anyMatch(hit -> hit.payload().content().contains(demoCase.expectedContains()));
            assertTrue(docHit, "自研检索没召回期望文档 " + demoCase.expectedDoc() + "；实际命中 "
                    + selfHits.stream().map(hit -> hit.payload().sourcePath() + "#" + hit.payload().chunkIndex()).toList());
            assertTrue(sources(selfHits).contains(demoCase.expectedDoc()),
                    "程序化附加的出处清单里应包含期望文档: " + demoCase.expectedDoc());
            assertFalse(selfAnswer == null || selfAnswer.isBlank(), "回答为空: " + demoCase.question());
            assertFalse(frameworkAnswer == null || frameworkAnswer.isBlank(), "回答为空: " + demoCase.question());
            if (chunkHit) {
                chunkHits++;
            }

            report.append("\n## 问题：").append(demoCase.question()).append("\n\n")
                    .append("期望出处：`").append(demoCase.expectedDoc()).append("`（答案字面串：")
                    .append(demoCase.expectedContains()).append("）\n\n")
                    .append("自研检索（生产同款参数 top-").append(TOP_K).append("）：文档命中 ")
                    .append(docHit ? "是" : "否").append("、答案片段命中 ").append(chunkHit ? "是" : "否").append("\n\n")
                    .append("### 检索命中（自研：混合 + 精排，top-").append(TOP_K).append("）\n\n")
                    .append(citeSelf(selfHits)).append('\n')
                    .append("### 检索命中（框架原生：向量单路，top-").append(TOP_K).append("）\n\n")
                    .append(citeFramework(frameworkHits)).append('\n')
                    .append("### 回答（自研检索 → AiServices）\n\n").append(selfAnswer.strip()).append("\n\n")
                    .append("来源（由检索结果程序化附加，不交给模型生成）：").append(sources(selfHits)).append("\n\n")
                    .append("### 回答（框架原生检索 → AiServices）\n\n").append(frameworkAnswer.strip()).append("\n\n")
                    .append("来源（由检索结果程序化附加）：").append(sourcesOf(frameworkHits)).append("\n");

            System.out.println("  · " + demoCase.question() + "\n    自研命中 " + selfHits.size()
                    + " 条 / 框架命中 " + frameworkHits.size() + " 条，两条回答均已生成");
        }

        report.append("\n## 小结\n\n")
                .append("- 三条问题的期望文档全部被自研检索召回（文档级 3/3）；\n")
                .append("- 生产同款参数（top-").append(TOP_K).append("）下，答案片段进前 ").append(TOP_K)
                .append(" 的有 ").append(chunkHits).append("/").append(CASES.size())
                .append(" 题 —— 这正是 D-22 记的片段级短板在生产设置下的现场表现；\n")
                .append("- 两条链路的回答都由 AiServices 生成（同一个 assistant 接口、同一个 ChatModel，")
                .append("只换 ContentRetriever）；出处清单由代码从检索结果拼出 —— 模型可以编路径，")
                .append("检索结果里的 sourcePath 是真值，这一条也适用于生产。\n");
        Files.createDirectories(REPORT_FILE.getParent());
        Files.writeString(REPORT_FILE, report.toString(), StandardCharsets.UTF_8);
        System.out.println("  演示记录已写入: " + REPORT_FILE.toAbsolutePath());
        System.out.println("  生产参数下答案片段进 top-" + TOP_K + " 的题数: " + chunkHits + "/" + CASES.size());
    }

    // ==================== 自研检索（与生产 KnowledgeRetriever 同口径） ====================

    /**
     * 给每段检索结果加上来源头 —— 模型看不到我们库里的 doc_id 字段，只有把它写进正文才会被「引用」。
     * 生产链路里这一步由 {@code KnowledgeRetriever#buildContext} 负责（拼引用），这里用一个包装器做同一件事：
     * 也顺便演示「框架的 ContentRetriever 是可组合的」。
     */
    private static List<Content> withSourceHeaders(List<Content> contents) {
        return contents.stream().map(Langchain4jRagAssistantTest::withSourceHeader).toList();
    }

    private static ContentRetriever withSourceHeaders(ContentRetriever delegate) {
        return query -> withSourceHeaders(delegate.retrieve(query));
    }

    private static Content withSourceHeader(Content content) {
        String source = content.textSegment().metadata().getString(DOC_ID_KEY);
        if (source == null || content.textSegment().text().startsWith("[来源:")) {
            return content;
        }
        return Content.from(TextSegment.from("[来源: " + source + "]\n" + content.textSegment().text()));
    }

    private List<HybridFusion.Fused<KnowledgeVectorIndex.Entry>> selfSearch(String query, RetrievalIndex index,
                                                                           EmbeddingModel embedder, String modelId,
                                                                           Reranker reranker) {
        float[] queryVector = embedder.embed(query).content().vector();
        EmbeddingCodec.normalize(queryVector);
        List<KnowledgeVectorIndex.Scored> vectorHits =
                index.searchVector(queryVector, modelId, CANDIDATES);
        List<KnowledgeVectorIndex.Scored> keywordHits = index.searchKeyword(query, CANDIDATES);
        List<HybridFusion.Fused<KnowledgeVectorIndex.Entry>> fused =
                HybridFusion.fuse(vectorHits, keywordHits, VECTOR_WEIGHT);
        if (reranker != null) {
            // 送精排的文本与生产同口径：有标题路径就拼上 heading 再送模型
            List<String> texts = fused.stream().map(hit -> {
                String heading = hit.payload().heading();
                return heading == null || heading.isBlank()
                        ? hit.payload().content()
                        : heading + "\n" + hit.payload().content();
            }).toList();
            List<Double> scores = reranker.score(query, texts);
            HybridFusion.applyRerankScores(fused, scores, HybridFusion.DEFAULT_RERANK_WEIGHT);
        }
        return fused.size() > TOP_K ? fused.subList(0, TOP_K) : fused;
    }

    private RetrievalIndex buildIndex(List<Doc> docs, EmbeddingModel embedder, String modelId, int dimension) {
        List<KnowledgeChunk> chunks = new ArrayList<>();
        Map<Long, KnowledgeDocument> documentsById = new LinkedHashMap<>();
        RagProperties props = new RagProperties();
        ReflectionTestUtils.setField(props, "chunkMaxChars", CHUNK_MAX_CHARS);
        ReflectionTestUtils.setField(props, "chunkOverlapChars", CHUNK_OVERLAP_CHARS);
        TextSplitter splitter = new TextChunker(props);
        long documentId = 1L;
        for (Doc doc : docs) {
            KnowledgeDocument document = new KnowledgeDocument(KnowledgeDocument.SOURCE_RESOURCE,
                    doc.id(), doc.title(), "demo-hash-" + documentId, modelId, dimension);
            document.setId(documentId);
            documentsById.put(documentId, document);
            List<TextChunker.Chunk> pieces = splitter.split(doc.text());
            for (TextChunker.Chunk piece : pieces) {
                float[] vector = embedder.embed(piece.text()).content().vector();
                EmbeddingCodec.normalize(vector);
                chunks.add(new KnowledgeChunk(documentId, piece.index(), piece.heading(), piece.text(),
                        EmbeddingCodec.encode(vector), vector.length, modelId));
            }
            documentId++;
        }
        KnowledgeVectorIndex index = new KnowledgeVectorIndex(true);
        index.rebuild(chunks, documentsById);
        return new InMemoryRetrievalIndex(index);
    }

    /** 精排属性：走生产的装配（RagConfiguration#ragReranker），失败时它自己降级成 noop */
    private static RagProperties demoRagProperties(String apiKey) {
        RagProperties props = new RagProperties();
        ReflectionTestUtils.setField(props, "rerankEnabled", true);
        ReflectionTestUtils.setField(props, "embeddingApiKey", apiKey);
        ReflectionTestUtils.setField(props, "rerankModel", "gte-rerank-v2");
        return props;
    }

    // ==================== 语料与引用渲染 ====================

    private static List<Doc> loadCorpus() throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(CORPUS_DIR)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
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
        return docs;
    }

    private static String frontMatter(String raw, String key, String fallback) {
        String text = raw.replace("\r\n", "\n");
        if (!text.startsWith("---")) {
            return fallback;
        }
        int end = text.indexOf("\n---", 3);
        if (end < 0) {
            return fallback;
        }
        for (String line : text.substring(3, end).split("\n")) {
            if (line.startsWith(key + ":")) {
                return line.substring(key.length() + 1).trim();
            }
        }
        return fallback;
    }

    private static String stripFrontMatter(String raw) {
        String text = raw.replace("\r\n", "\n");
        if (!text.startsWith("---")) {
            return text;
        }
        int end = text.indexOf("\n---", 3);
        int body = end < 0 ? -1 : text.indexOf('\n', end + 1);
        return body < 0 ? text : text.substring(body + 1);
    }

    private static String citeSelf(List<HybridFusion.Fused<KnowledgeVectorIndex.Entry>> hits) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            KnowledgeVectorIndex.Entry entry = hits.get(i).payload();
            sb.append("- [").append(i + 1).append("] `").append(entry.sourcePath()).append("`")
                    .append(entry.heading() == null ? "" : " > " + entry.heading())
                    .append(" ｜ ").append(firstLine(entry.content())).append('\n');
        }
        return sb.toString();
    }

    private static String citeFramework(List<Content> hits) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            TextSegment segment = hits.get(i).textSegment();
            sb.append("- [").append(i + 1).append("] `")
                    .append(segment.metadata().getString(DOC_ID_KEY)).append("`")
                    .append(" ｜ ").append(firstLine(segment.text())).append('\n');
        }
        return sb.toString();
    }

    private static String firstLine(String text) {
        String stripped = text.strip();
        int newline = stripped.indexOf('\n');
        String line = newline < 0 ? stripped : stripped.substring(0, newline);
        return line.length() > 90 ? line.substring(0, 90) + "…" : line;
    }

    /**
     * 出处清单由代码从检索结果拼出来，**不交给模型生成** ——
     * 模型可以编路径，而检索结果里的 {@code sourcePath} 是真值；这也是生产里拼引用的同一条原则。
     */
    private static String sources(List<HybridFusion.Fused<KnowledgeVectorIndex.Entry>> hits) {
        return hits.stream().map(hit -> hit.payload().sourcePath()).distinct()
                .reduce((left, right) -> left + "、" + right).orElse("（无）");
    }

    private static String sourcesOf(List<Content> hits) {
        return hits.stream().map(content -> content.textSegment().metadata().getString(DOC_ID_KEY))
                .filter(java.util.Objects::nonNull).distinct()
                .reduce((left, right) -> left + "、" + right).orElse("（无）");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
