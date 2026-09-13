package io.github.wangyangxu.ailink.rag;

import java.util.List;

/**
 * 检索结果：命中片段 + 已按预算拼装好的上下文文本。
 * {@code contextText} 直接注入 system 消息或作为 tool 结果回传模型。
 */
public record RetrievalResult(String query, List<RetrievedChunk> chunks, String contextText) {

    public static RetrievalResult empty(String query) {
        return new RetrievalResult(query, List.of(), "");
    }

    public boolean isEmpty() {
        return chunks == null || chunks.isEmpty();
    }
}