package io.github.wangyangxu.ailink.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HashingEmbeddingModelTest {

    private final HashingEmbeddingModel model = new HashingEmbeddingModel(256);

    private float[] vectorOf(String text) {
        return model.embedAll(List.of(TextSegment.from(text))).content().get(0).vector();
    }

    @Test
    void exposesConfiguredDimensionAndStableModelId() {
        assertEquals(256, model.dimension());
        assertEquals(HashingEmbeddingModel.MODEL_NAME, model.modelName());
    }

    @Test
    void isDeterministicAndNormalized() {
        float[] first = vectorOf("简历模板 ATS 关键字");
        float[] second = vectorOf("简历模板 ATS 关键字");

        assertTrue(java.util.Arrays.equals(first, second), "同一输入应得到完全一致的向量");
        double norm = 0d;
        for (float v : first) {
            norm += v * v;
        }
        assertEquals(1d, Math.sqrt(norm), 1e-5);
    }

    @Test
    void similarTextScoresHigherThanUnrelatedText() {
        float[] query = vectorOf("简历模板怎么写");
        double similar = EmbeddingCodec.cosine(query, vectorOf("简历模板写作规范"));
        double unrelated = EmbeddingCodec.cosine(query, vectorOf("今天杭州的天气和温度"));

        assertTrue(similar > unrelated,
                "词面相近的文本余弦相似度应更高: similar=" + similar + ", unrelated=" + unrelated);
    }

    @Test
    void handlesShortText() {
        List<Embedding> embeddings = model.embedAll(List.of(TextSegment.from("AI"))).content();

        assertEquals(1, embeddings.size());
        assertEquals(256, embeddings.get(0).vector().length);
    }

    @Test
    void blankTextIsRejectedBeforeReachingTheModel() {
        assertThrows(IllegalArgumentException.class, () -> TextSegment.from(" "));
    }
}