package io.github.wangyangxu.ailink.rag;

import io.github.wangyangxu.ailink.mapper.KnowledgeChunkMapper;
import io.github.wangyangxu.ailink.mapper.KnowledgeDocumentMapper;
import io.github.wangyangxu.ailink.model.KnowledgeDocument;
import io.github.wangyangxu.ailink.service.MetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 真实资产冒烟测试：把 classpath 下的 resume-builder/** 真灌一遍，
 * 验证「资源扫描 → 相对路径 → 切分 → 向量化 → 检索」整条链路在离线（本地词法向量）下能跑通。
 * 这层测试盯的是工程细节（资源定位、同名文件、编码），而不是算法本身。
 */
class ResourceAssetIndexingSmokeTest {

    private final KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
    private final KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
    private final KnowledgeVectorIndex index = new KnowledgeVectorIndex();
    private final HashingEmbeddingModel embedder = new HashingEmbeddingModel(512);
    private final RagProperties props = new RagProperties();

    private KnowledgeIndexService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(props, "enabled", true);
        ReflectionTestUtils.setField(props, "indexOnStartup", false);
        ReflectionTestUtils.setField(props, "chunkMaxChars", 800);
        ReflectionTestUtils.setField(props, "chunkOverlapChars", 120);
        ReflectionTestUtils.setField(props, "retrieveTopK", 4);
        ReflectionTestUtils.setField(props, "candidateMultiplier", 3);
        ReflectionTestUtils.setField(props, "maxPerDocument", 2);
        ReflectionTestUtils.setField(props, "vectorWeight", 0.65d);
        ReflectionTestUtils.setField(props, "contextMaxChars", 4000);
        ReflectionTestUtils.setField(props, "embeddingDimension", 512);

        AtomicLong ids = new AtomicLong(1);
        doAnswer(invocation -> {
            KnowledgeDocument document = invocation.getArgument(0);
            document.setId(ids.getAndIncrement());
            return null;
        }).when(documentMapper).insert(any(KnowledgeDocument.class));
        when(documentMapper.findByPath(any())).thenReturn(null);

        service = new KnowledgeIndexService(props, new TextChunker(props), new InMemoryRetrievalIndex(index),
                embedder, documentMapper, chunkMapper);
    }

    @Test
    void indexesBundledResumeAssetsAndAnswersFromThem() {
        int documents = service.indexResourceAssets();

        assertTrue(documents >= 30, "内置资产应全部入库，实际 " + documents);
        assertTrue(index.size() >= documents, "每份文档至少切出一个片段，实际片段 " + index.size());

        KnowledgeRetriever retriever = new KnowledgeRetriever(props, new InMemoryRetrievalIndex(index), service,
                embedder, Reranker.noop(), new MetricsService());
        RetrievalResult result = retriever.retrieve("学生简历怎么写项目经历", 3);

        assertFalse(result.isEmpty(), "应能从内置资产中召回内容");
        assertTrue(result.chunks().stream().anyMatch(c -> c.sourcePath().startsWith("resume-builder/")),
                "命中的来源应是内置资产");
        assertTrue(result.contextText().contains("来源：resume-builder/"));
    }

    @Test
    void resourcePathsKeepSubdirectories() {
        service.indexResourceAssets();

        assertTrue(index.searchKeyword("ATS", 5).stream()
                        .anyMatch(s -> s.entry().sourcePath().contains("/")),
                "子目录应体现在来源路径里，避免同名文件互相覆盖");
    }
}
