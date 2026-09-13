package io.github.wangyangxu.ailink.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.github.wangyangxu.ailink.model.KnowledgeChunk;
import io.github.wangyangxu.ailink.model.KnowledgeDocument;
import io.github.wangyangxu.ailink.service.MetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeRetrieverTest {

    private static final String MODEL_ID = HashingEmbeddingModel.MODEL_NAME;

    private final HashingEmbeddingModel embedder = new HashingEmbeddingModel(256);
    private final KnowledgeVectorIndex index = new KnowledgeVectorIndex();
    private final MetricsService metrics = new MetricsService();
    private final RagProperties props = new RagProperties();

    private final Map<Long, KnowledgeDocument> documents = new HashMap<>();

    private void doc(long id, String path) {
        KnowledgeDocument document = new KnowledgeDocument(KnowledgeDocument.SOURCE_RESOURCE, path, path,
                "hash-" + id, MODEL_ID, 256);
        document.setId(id);
        documents.put(id, document);
    }

    private KnowledgeChunk chunk(long docId, int index, String heading, String text) {
        float[] vector = embedder.embedAll(List.of(TextSegment.from(text))).content().get(0).vector();
        return new KnowledgeChunk(docId, index, heading, text, EmbeddingCodec.encode(vector), vector.length, MODEL_ID);
    }

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(props, "enabled", true);
        ReflectionTestUtils.setField(props, "retrieveTopK", 3);
        ReflectionTestUtils.setField(props, "candidateMultiplier", 3);
        ReflectionTestUtils.setField(props, "maxPerDocument", 2);
        ReflectionTestUtils.setField(props, "vectorWeight", 0.65d);
        ReflectionTestUtils.setField(props, "contextMaxChars", 4000);

        doc(1L, "resume-builder/references/resume_writing_rules.md");
        doc(2L, "resume-builder/references/online_role_research.md");
        doc(3L, "data/documents/我的简历.docx");
        index.rebuild(List.of(
                chunk(1L, 0, "写作规则", "简历写作要突出量化结果，例如把负责模块的性能提升写成具体数字。"),
                chunk(1L, 1, "写作规则", "简历里避免形容词堆砌，用动词开头描述个人贡献。"),
                chunk(2L, 0, "岗位调研", "岗位调研要收集招聘 JD、行业动态与竞品信息。"),
                chunk(3L, 0, "个人经历", "候选人负责过支付网关重构，把响应时间从 800ms 降到 120ms。")
        ), documents);
    }

    private KnowledgeRetriever retriever(EmbeddingModel model, Reranker reranker) {
        KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
        when(indexService.embeddingModelId()).thenReturn(MODEL_ID);
        return new KnowledgeRetriever(props, new InMemoryRetrievalIndex(index), indexService, model, reranker, metrics);
    }

    @Test
    void retrievesChunksWithCitationAndContext() {
        RetrievalResult result = retriever(embedder, Reranker.noop()).retrieve("简历怎么写才突出量化结果", 3);

        assertFalse(result.isEmpty());
        RetrievedChunk top = result.chunks().get(0);
        assertEquals("resume-builder/references/resume_writing_rules.md", top.sourcePath());
        assertTrue(top.citation().contains("resume-builder/references/resume_writing_rules.md"));
        assertTrue(top.channel().equals("hybrid") || top.channel().equals("vector") || top.channel().equals("keyword"));

        assertTrue(result.contextText().contains("【片段 1】"));
        assertTrue(result.contextText().contains("来源：resume-builder/references/resume_writing_rules.md"));
        assertTrue(result.contextText().contains("[知识库检索结果]"));
    }

    @Test
    void capsChunksPerDocumentToKeepResultsDiverse() {
        ReflectionTestUtils.setField(props, "maxPerDocument", 1);

        RetrievalResult result = retriever(embedder, Reranker.noop()).retrieve("简历写作规则与量化结果", 3);

        long fromDoc1 = result.chunks().stream().filter(c -> c.documentId() == 1L).count();
        assertTrue(fromDoc1 <= 1, "同一文档最多占 maxPerDocument 条，实际 " + fromDoc1);
    }

    @Test
    void degradesToKeywordOnlyWhenEmbeddingFails() {
        EmbeddingModel broken = new EmbeddingModel() {
            @Override
            public int dimension() {
                return 256;
            }

            @Override
            public String modelName() {
                return MODEL_ID;
            }

            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                throw new IllegalStateException("向量服务不可用");
            }
        };

        RetrievalResult result = retriever(broken, Reranker.noop()).retrieve("招聘 JD 行业动态", 3);

        assertFalse(result.isEmpty(), "向量失败时应由关键词通道兜住");
        assertEquals("keyword", result.chunks().get(0).channel());
    }

    @Test
    void appliesRerankerWhenAvailable() {
        Reranker favouringJobResearch = (query, candidates) -> candidates.stream()
                .map(text -> text.contains("岗位调研") ? 1.0d : 0.1d)
                .toList();
        // 查询同时命中「简历写作」与「岗位调研」两个片段，确保候选集里两者都在
        RetrievalResult result = retriever(embedder, favouringJobResearch)
                .retrieve("简历写作 量化结果 岗位调研", 3);

        assertFalse(result.isEmpty());
        assertEquals("rerank", result.chunks().get(0).channel());
        assertTrue(result.chunks().get(0).content().contains("岗位调研"),
                "精排分数最高的候选应排在首位");
    }

    @Test
    void emptyIndexYieldsEmptyResult() {
        KnowledgeVectorIndex empty = new KnowledgeVectorIndex();
        KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
        when(indexService.embeddingModelId()).thenReturn(MODEL_ID);
        KnowledgeRetriever retriever = new KnowledgeRetriever(props, new InMemoryRetrievalIndex(empty), indexService, embedder,
                Reranker.noop(), metrics);

        assertTrue(retriever.retrieve("任意问题", 3).isEmpty());
        assertFalse(retriever.isAvailable());
    }

    @Test
    void contextRespectsCharBudget() {
        ReflectionTestUtils.setField(props, "contextMaxChars", 700);

        RetrievalResult result = retriever(embedder, Reranker.noop()).retrieve("简历写作规则", 3);

        assertFalse(result.isEmpty());
        assertTrue(result.contextText().length() <= 1400,
                "上下文长度应受预算约束，实际 " + result.contextText().length());
    }
}