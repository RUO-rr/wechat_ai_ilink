package io.github.wangyangxu.ailink.model;

/**
 * RAG 知识库文档实体，对应 knowledge_document 表。
 * <p>
 * 一行代表一份被索引的文档（内置资产 / 用户上传文件）。
 * {@code contentHash} 保证幂等重建：内容未变时跳过切分与向量化；
 * {@code embeddingModel} 记录建索引时使用的向量模型，避免不同模型的向量混进同一个检索空间。
 */
public class KnowledgeDocument {

    /** 内置知识资产（classpath:resume-builder/**） */
    public static final String SOURCE_RESOURCE = "resource";
    /** 用户上传文件（data/documents、data/resumes） */
    public static final String SOURCE_USER_UPLOAD = "user_upload";

    public static final String STATUS_INDEXED = "indexed";
    public static final String STATUS_FAILED = "failed";

    private Long id;
    private String sourceType;
    private String sourcePath;
    private String title;
    private String contentHash;
    private Integer chunkCount;
    private String embeddingModel;
    private Integer dimension;
    private String status;
    private String createdAt;
    private String updatedAt;

    public KnowledgeDocument() {}

    public KnowledgeDocument(String sourceType, String sourcePath, String title,
                             String contentHash, String embeddingModel, Integer dimension) {
        this.sourceType = sourceType;
        this.sourcePath = sourcePath;
        this.title = title;
        this.contentHash = contentHash;
        this.embeddingModel = embeddingModel;
        this.dimension = dimension;
        this.chunkCount = 0;
        this.status = STATUS_INDEXED;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }

    public String getSourcePath() { return sourcePath; }
    public void setSourcePath(String sourcePath) { this.sourcePath = sourcePath; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }

    public Integer getChunkCount() { return chunkCount; }
    public void setChunkCount(Integer chunkCount) { this.chunkCount = chunkCount; }

    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }

    public Integer getDimension() { return dimension; }
    public void setDimension(Integer dimension) { this.dimension = dimension; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}