package io.github.wangyangxu.ailink.rag;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Points;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Qdrant 的连接与集合管理 —— 把「Qdrant 特有的东西」（gRPC 客户端、建集合、点数、payload 键名）
 * 收在一处，让 {@link EmbeddingStoreRetrievalIndex} 只依赖 LangChain4j 的 {@code EmbeddingStore} 抽象。
 * <p>
 * 这样切分有两个直接好处：换向量库（Milvus / pgvector）只改本类；单测里把 store 换成框架自带的
 * InMemoryEmbeddingStore 就能跑完整检索逻辑，不需要一台真的 Qdrant —— 只有「payload 与 filter
 * 真的能穿过 gRPC 往返」这一件事必须打真库，那是集成测试的职责。
 * <p>
 * 注意：LangChain4j 的 Qdrant 实现不会自动建集合，维度与距离要由我们显式声明
 * （距离用 Cosine，与内存实现的余弦打分保持同一语义）。
 */
public final class QdrantVectorStoreFactory {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStoreFactory.class);

    private QdrantVectorStoreFactory() {
    }

    /** 一条可用连接：检索用的 store + 管理用的点数接口。 */
    public record Connection(QdrantClient client, EmbeddingStore<TextSegment> store, VectorStoreAdmin admin) {
    }

    /** 建连接并确保集合存在；任何一步失败都抛给调用方，由装配层决定降级。 */
    public static Connection open(VectorStoreProperties props) throws Exception {
        QdrantGrpcClient.Builder builder =
                QdrantGrpcClient.newBuilder(props.getHost(), props.getPort(), props.isUseTls());
        if (props.hasApiKey()) {
            builder.withApiKey(props.getApiKey().trim());
        }
        QdrantClient client = new QdrantClient(builder.build());
        ensureCollection(client, props);
        EmbeddingStore<TextSegment> store = QdrantEmbeddingStore.builder()
                .client(client)
                .collectionName(props.getCollection())
                .payloadTextKey(props.getPayloadTextKey())
                .build();
        return new Connection(client, store, () -> countPoints(client, props));
    }

    /** 集合不存在就按配置的维度建一个。 */
    public static void ensureCollection(QdrantClient client, VectorStoreProperties props) throws Exception {
        String collection = props.getCollection();
        Boolean exists = client.collectionExistsAsync(collection)
                .get(props.getRequestTimeoutMs(), TimeUnit.MILLISECONDS);
        if (Boolean.TRUE.equals(exists)) {
            return;
        }
        client.createCollectionAsync(collection, Collections.VectorParams.newBuilder()
                        .setSize(props.getDimension())
                        .setDistance(Collections.Distance.Cosine)
                        .build())
                .get(props.getRequestTimeoutMs(), TimeUnit.MILLISECONDS);
        log.info("已创建 Qdrant 集合 {}（size={}, distance=Cosine）", collection, props.getDimension());
        // 过滤字段建索引：检索时按 embedding_model 过滤、删除时按 document_id 过滤，
        // 十万级点位上没有索引就只能全量后过滤，前面两个操作都会明显变慢。
        createPayloadIndex(client, props, EmbeddingStoreRetrievalIndex.KEY_DOCUMENT_ID, Points.FieldType.FieldTypeInteger);
        createPayloadIndex(client, props, EmbeddingStoreRetrievalIndex.KEY_MODEL, Points.FieldType.FieldTypeKeyword);
    }

    /** 建 payload 索引；失败不致命（字段索引已存在或类型不同），下次启动会再试一次。 */
    private static void createPayloadIndex(QdrantClient client, VectorStoreProperties props,
                                          String field, Points.FieldType type) {
        try {
            client.createPayloadIndexAsync(Points.CreateFieldIndexCollection.newBuilder()
                            .setCollectionName(props.getCollection())
                            .setFieldName(field)
                            .setFieldType(type)
                            .build(),
                    Duration.ofMillis(props.getRequestTimeoutMs()))
                    .get(props.getRequestTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debug("payload 索引 {} 创建失败（可忽略）: {}", field, e.toString());
        }
    }

    /** 集合当前点数；不可用返回 -1（表示「不知道」，不是 0）。 */
    public static long countPoints(QdrantClient client, VectorStoreProperties props) {
        try {
            Long count = client.countAsync(props.getCollection())
                    .get(props.getRequestTimeoutMs(), TimeUnit.MILLISECONDS);
            return count == null ? -1L : count;
        } catch (Exception e) {
            return -1L;
        }
    }
}