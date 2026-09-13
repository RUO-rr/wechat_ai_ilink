package io.github.wangyangxu.ailink.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.github.wangyangxu.ailink.mapper.KnowledgeChunkMapper;
import io.github.wangyangxu.ailink.mapper.KnowledgeDocumentMapper;
import io.github.wangyangxu.ailink.model.KnowledgeChunk;
import io.github.wangyangxu.ailink.model.KnowledgeDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeIndexServiceTest {

    private final KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
    private final KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
    private final KnowledgeVectorIndex index = new KnowledgeVectorIndex();
    private final HashingEmbeddingModel embedder = new HashingEmbeddingModel(256);
    private final RagProperties props = new RagProperties();

    private KnowledgeIndexService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(props, "enabled", true);
        ReflectionTestUtils.setField(props, "chunkMaxChars", 300);
        ReflectionTestUtils.setField(props, "chunkOverlapChars", 50);
        ReflectionTestUtils.setField(props, "embeddingDimension", 256);
        ReflectionTestUtils.setField(props, "embeddingModel", "local-test-model");
        // 模拟 useGeneratedKeys：insert 后回填自增主键
        doAnswer(invocation -> {
            KnowledgeDocument document = invocation.getArgument(0);
            document.setId(9L);
            return null;
        }).when(documentMapper).insert(any(KnowledgeDocument.class));
        service = new KnowledgeIndexService(props, new TextChunker(props), new InMemoryRetrievalIndex(index),
                embedder, documentMapper, chunkMapper);
    }

    /** 把分批 insertBatch 的片段汇总起来检查 */
    private List<KnowledgeChunk> capturedChunks() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgeChunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(chunkMapper, atLeastOnce()).insertBatch(captor.capture());
        List<KnowledgeChunk> all = new ArrayList<>();
        captor.getAllValues().forEach(all::addAll);
        return all;
    }

    @Test
    void indexesTextIntoChunksAndVectorizes() {
        when(documentMapper.findByPath(anyString())).thenReturn(null);
        String text = "# 规则\n\n" + "简历写作要突出量化结果。".repeat(60);

        KnowledgeIndexService.IndexOutcome outcome =
                service.indexText(KnowledgeDocument.SOURCE_RESOURCE, "resume-builder/x.md", "x.md", text);

        assertTrue(outcome.refreshed());
        assertTrue(outcome.chunkCount() > 1);
        assertEquals(outcome.chunkCount(), outcome.vectorizedCount(), "本地词法向量应全部成功");
        assertEquals(9L, outcome.documentId());
        verify(documentMapper).insert(any(KnowledgeDocument.class));

        List<KnowledgeChunk> chunks = capturedChunks();
        assertTrue(chunks.stream().allMatch(c -> c.getEmbedding() != null));
        assertTrue(chunks.stream().allMatch(c -> c.getEmbeddingModel() != null));
        assertTrue(chunks.stream().allMatch(c -> c.getDocumentId() != null));
        assertEquals(chunks.size(), index.size(), "入库后内存索引应立即可检索");
    }

    @Test
    void skipsWhenContentAndModelUnchanged() {
        when(documentMapper.findByPath(anyString())).thenReturn(null);
        String text = "# 规则\n\n" + "内容稳定不变。".repeat(50);
        service.indexText(KnowledgeDocument.SOURCE_RESOURCE, "resume-builder/x.md", "x.md", text);

        ArgumentCaptor<KnowledgeDocument> inserted = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(documentMapper).insert(inserted.capture());
        when(documentMapper.findByPath(anyString())).thenReturn(inserted.getValue());

        KnowledgeIndexService.IndexOutcome second =
                service.indexText(KnowledgeDocument.SOURCE_RESOURCE, "resume-builder/x.md", "x.md", text);

        assertFalse(second.refreshed(), "内容与向量模型都没变时不应重复灌库");
        verify(chunkMapper, never()).deleteByDocumentId(any());
        assertEquals(0, second.vectorizedCount());
    }

    @Test
    void reindexesWhenEmbeddingModelChanges() {
        when(documentMapper.findByPath(anyString())).thenReturn(null);
        String text = "# 规则\n\n" + "内容不变但换了向量模型。".repeat(50);
        service.indexText(KnowledgeDocument.SOURCE_RESOURCE, "resume-builder/x.md", "x.md", text);

        ArgumentCaptor<KnowledgeDocument> inserted = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(documentMapper).insert(inserted.capture());
        KnowledgeDocument stale = inserted.getValue();
        stale.setId(7L);
        stale.setEmbeddingModel("dashscope:text-embedding-v4@1024");
        when(documentMapper.findByPath(anyString())).thenReturn(stale);

        KnowledgeIndexService.IndexOutcome second =
                service.indexText(KnowledgeDocument.SOURCE_RESOURCE, "resume-builder/x.md", "x.md", text);

        assertTrue(second.refreshed(), "向量模型变化应触发重建");
        verify(chunkMapper).deleteByDocumentId(7L);
        verify(documentMapper).updateStats(any(KnowledgeDocument.class));
    }

    @Test
    void embeddingFailureKeepsTextSearchable() {
        EmbeddingModel broken = new EmbeddingModel() {
            @Override
            public int dimension() {
                return 256;
            }

            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                throw new IllegalStateException("模型不可用");
            }
        };
        KnowledgeIndexService degraded = new KnowledgeIndexService(props, new TextChunker(props),
                new InMemoryRetrievalIndex(index), broken, documentMapper, chunkMapper);
        when(documentMapper.findByPath(anyString())).thenReturn(null);

        KnowledgeIndexService.IndexOutcome outcome = degraded.indexText(
                KnowledgeDocument.SOURCE_RESOURCE, "resume-builder/y.md", "y.md",
                "# 规则\n\n" + "向量失败也要能检索。".repeat(50));

        assertEquals(0, outcome.vectorizedCount());
        assertTrue(outcome.chunkCount() > 0);
        List<KnowledgeChunk> chunks = capturedChunks();
        assertTrue(chunks.stream().allMatch(c -> c.getEmbedding() == null));
        assertFalse(index.searchKeyword("向量失败也要能检索", 3).isEmpty(), "关键词通道应仍然可用");
    }

    @Test
    void coverageSampleReachesEndOfLongDocument() {
        when(documentMapper.findByPath(anyString())).thenReturn(null);
        StringBuilder text = new StringBuilder("# 长文档\n\n");
        for (int i = 0; i < 40; i++) {
            text.append("第").append(i).append("节内容。")
                    .append("填充说明文字。".repeat(8)).append("\n\n");
        }
        text.append("结尾标记：文档最后一节的内容。");

        KnowledgeIndexService.IndexOutcome outcome = service.indexText(
                KnowledgeDocument.SOURCE_USER_UPLOAD, "data/documents/long.docx", "long.docx", text.toString());
        String sample = service.coverageSample(outcome.documentId(), 1500);

        assertTrue(sample.length() > 200, "采样不应为空");
        assertTrue(sample.contains("结尾标记"), "覆盖率采样应覆盖到文末，而不是只取开头");
    }

    @Test
    void embeddingModelIdIsStableForHashingFallback() {
        assertEquals(HashingEmbeddingModel.MODEL_NAME, service.embeddingModelId());
    }

    @Test
    void statsReportDatabaseAndIndexNumbers() {
        when(documentMapper.countAll()).thenReturn(3);

        KnowledgeIndexService.IndexStats stats = service.stats();

        assertEquals(3, stats.documents());
        assertEquals(HashingEmbeddingModel.MODEL_NAME, stats.embeddingModelId());
    }

    @Test
    void rebuildFromDatabaseRestoresIndexAndHeadings() {
        KnowledgeDocument doc = new KnowledgeDocument(KnowledgeDocument.SOURCE_RESOURCE, "resume-builder/z.md",
                "z.md", "hash", HashingEmbeddingModel.MODEL_NAME, 256);
        doc.setId(5L);
        when(documentMapper.findAll()).thenReturn(List.of(doc));
        float[] vector = embedder.embedAll(List.of(TextSegment.from("数据库里的片段"))).content().get(0).vector();
        when(chunkMapper.findAll()).thenReturn(new ArrayList<>(List.of(
                new KnowledgeChunk(5L, 0, "章节", "数据库里的片段", EmbeddingCodec.encode(vector), 256,
                        HashingEmbeddingModel.MODEL_NAME))));

        service.rebuildFromDatabase();

        assertEquals(1, index.size());
        assertEquals("章节", index.entriesOfDocument(5L).get(0).heading());
        verify(documentMapper, times(1)).findAll();
    }
}