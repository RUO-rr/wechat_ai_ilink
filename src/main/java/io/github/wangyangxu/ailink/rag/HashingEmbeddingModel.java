package io.github.wangyangxu.ailink.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 离线降级向量模型 —— 词法哈希（feature hashing）实现，零网络、零依赖、结果可复现。
 * <p>
 * <b>定位</b>：当未配置 DashScope 密钥（本地开发 / CI / 单测）时，让整条检索链路仍然可用，
 * 而不是让 RAG 直接不可用。它的相似度本质是「token 重合度」，只能做字面召回，
 * 不具备真正的语义泛化能力 —— 因此：
 * <ul>
 *   <li>检索侧仍会跑「关键词 + 向量」双通道，向量通道退化为关键词的平滑版本；</li>
 *   <li>片段会记录 {@code embedding_model}，配置真实模型后重新索引即可切回语义检索，
 *       两种模型的向量不会混进同一个检索空间。</li>
 * </ul>
 */
public class HashingEmbeddingModel implements EmbeddingModel {

    /** 模型标识：写入 knowledge_chunk.embedding_model，用于检索时隔离不同向量空间 */
    public static final String MODEL_NAME = "local-hashing-v1";

    private final int dimension;

    public HashingEmbeddingModel(int dimension) {
        this.dimension = Math.max(32, dimension);
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public String modelName() {
        return MODEL_NAME;
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        List<Embedding> embeddings = new ArrayList<>(segments.size());
        for (TextSegment segment : segments) {
            embeddings.add(Embedding.from(vectorize(segment.text())));
        }
        return Response.from(embeddings);
    }

    /** 词频取 1+log(tf) 压缩，符号哈希降低碰撞带来的偏置，最后 L2 归一化。 */
    private float[] vectorize(String text) {
        Map<String, Integer> termFreq = new HashMap<>();
        for (String token : Tokenizers.tokenize(text)) {
            termFreq.merge(token, 1, Integer::sum);
        }
        float[] vector = new float[dimension];
        for (Map.Entry<String, Integer> e : termFreq.entrySet()) {
            int h = e.getKey().hashCode();
            int idx = Math.floorMod(h, dimension);
            float sign = ((h >>> 16) & 1) == 0 ? 1f : -1f;
            float weight = (float) (1d + Math.log(e.getValue()));
            vector[idx] += sign * weight;
        }
        EmbeddingCodec.normalize(vector);
        return vector;
    }
}