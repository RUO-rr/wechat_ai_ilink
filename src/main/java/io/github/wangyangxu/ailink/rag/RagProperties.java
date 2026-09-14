package io.github.wangyangxu.ailink.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * RAG 链路配置（application.properties 的 {@code rag.*}）。
 * 用 @Value 保持与项目其余配置读取方式一致，集中在一处便于查阅与测试。
 */
@Component
public class RagProperties {

    @Value("${rag.enabled:true}")
    private boolean enabled;

    @Value("${rag.index-on-startup:true}")
    private boolean indexOnStartup;

    @Value("${rag.chunk.max-chars:800}")
    private int chunkMaxChars;

    @Value("${rag.chunk.overlap-chars:120}")
    private int chunkOverlapChars;

    /**
     * 切分器实现：{@code self}（默认，自研标题感知切分）或 {@code langchain4j}
     * （LangChain4j 的 {@code DocumentSplitters.recursive}）。见 {@link TextSplitter}。
     */
    @Value("${rag.splitter:self}")
    private String splitter;

    @Value("${rag.retrieve.top-k:4}")
    private int retrieveTopK;

    @Value("${rag.retrieve.candidate-multiplier:3}")
    private int candidateMultiplier;

    @Value("${rag.retrieve.max-per-document:2}")
    private int maxPerDocument;

    @Value("${rag.fuse.vector-weight:0.65}")
    private double vectorWeight;

    @Value("${rag.context.max-chars:4000}")
    private int contextMaxChars;

    @Value("${rag.embedding.base-url:}")
    private String embeddingBaseUrl;

    @Value("${rag.embedding.api-key:}")
    private String embeddingApiKey;

    @Value("${rag.embedding.model:text-embedding-v4}")
    private String embeddingModel;

    @Value("${rag.embedding.dimension:1024}")
    private int embeddingDimension;

    @Value("${rag.embedding.fallback-dimension:512}")
    private int fallbackDimension;

    @Value("${rag.rerank.enabled:false}")
    private boolean rerankEnabled;

    @Value("${rag.rerank.model:gte-rerank-v2}")
    private String rerankModel;

    @Value("${rag.upload-dirs:data/documents,data/resumes}")
    private String uploadDirs;

    public boolean isEnabled() { return enabled; }
    public boolean isIndexOnStartup() { return indexOnStartup; }
    public int getChunkMaxChars() { return chunkMaxChars; }
    public int getChunkOverlapChars() { return chunkOverlapChars; }
    public String getSplitter() { return splitter; }
    public int getRetrieveTopK() { return retrieveTopK; }
    public int getCandidateMultiplier() { return candidateMultiplier; }
    public int getMaxPerDocument() { return maxPerDocument; }
    public double getVectorWeight() { return vectorWeight; }
    public int getContextMaxChars() { return contextMaxChars; }
    public String getEmbeddingBaseUrl() { return embeddingBaseUrl; }
    public String getEmbeddingApiKey() { return embeddingApiKey; }
    public String getEmbeddingModel() { return embeddingModel; }
    public int getEmbeddingDimension() { return embeddingDimension; }
    public int getFallbackDimension() { return fallbackDimension; }
    public boolean isRerankEnabled() { return rerankEnabled; }
    public String getRerankModel() { return rerankModel; }

    public List<String> getUploadDirList() {
        return Arrays.stream(uploadDirs.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
