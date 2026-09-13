package io.github.wangyangxu.ailink.rag;

/**
 * 检索命中的片段 —— 带来源与分数，便于回答时给出可核对的引用，也便于排查「为什么召回这条」。
 *
 * @param channel 命中通道：vector（语义）/ keyword（字面）/ hybrid（两路都命中）/ rerank（经精排调序）
 */
public record RetrievedChunk(long documentId,
                             int chunkIndex,
                             String sourcePath,
                             String title,
                             String heading,
                             String content,
                             double score,
                             String channel) {

    /** 片段唯一标识：同一文档内序号唯一（入库时未回填自增主键，用文档+序号更稳）。 */
    public String identity() {
        return documentId + "#" + chunkIndex;
    }

    /** 引用位置：来源文件（+ 章节标题） */
    public String citation() {
        if (heading == null || heading.isBlank()) {
            return sourcePath;
        }
        return sourcePath + " > " + heading;
    }
}