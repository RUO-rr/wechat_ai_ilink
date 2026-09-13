package io.github.wangyangxu.ailink.rag;

/**
 * 远端向量库的管理动作 —— 与读写主路径分开：读写是高频、必须稳定；管理（点数、建集合）是低频，
 * 而且只有远端实现才需要。分开之后 {@link EmbeddingStoreRetrievalIndex} 只依赖 LangChain4j 的
 * {@code EmbeddingStore} 抽象，单测里换成框架自带的 InMemoryEmbeddingStore 就能跑，
 * 不需要一台真的 Qdrant。
 */
@FunctionalInterface
public interface VectorStoreAdmin {

    /** 远端集合当前点数；管理通道不可用时返回 -1（表示「不知道」，不是 0）。 */
    long count();

    /** 没有管理通道时使用（纯内存替身场景）。 */
    VectorStoreAdmin NONE = () -> -1L;
}