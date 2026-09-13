package io.github.wangyangxu.ailink.model;

/**
 * RAG 知识库片段实体，对应 knowledge_chunk 表 —— 检索的最小单位。
 * <p>
 * {@code heading} 记录该片段所属的 Markdown 标题路径（如 {@code 三、代码质量改进 > 3.2 测试}），
 * 用于回答时给出可核对的引用位置；{@code embedding} 为 JSON 数组文本，
 * 为 NULL 表示该片段只有关键词召回路可用（向量模型不可用时的降级状态）。
 */
public class KnowledgeChunk {

    private Long id;
    private Long documentId;
    private int chunkIndex;
    private String heading;
    private String content;
    private String embedding;
    private Integer dimension;
    private String embeddingModel;
    private String createdAt;

    public KnowledgeChunk() {}

    public KnowledgeChunk(Long documentId, int chunkIndex, String heading, String content,
                          String embedding, Integer dimension, String embeddingModel) {
        this.documentId = documentId;
        this.chunkIndex = chunkIndex;
        this.heading = heading;
        this.content = content;
        this.embedding = embedding;
        this.dimension = dimension;
        this.embeddingModel = embeddingModel;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }

    public int getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(int chunkIndex) { this.chunkIndex = chunkIndex; }

    public String getHeading() { return heading; }
    public void setHeading(String heading) { this.heading = heading; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getEmbedding() { return embedding; }
    public void setEmbedding(String embedding) { this.embedding = embedding; }

    public Integer getDimension() { return dimension; }
    public void setDimension(Integer dimension) { this.dimension = dimension; }

    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}