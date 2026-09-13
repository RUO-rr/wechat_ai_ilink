package io.github.wangyangxu.ailink.config;

import io.github.wangyangxu.ailink.rag.EmbeddingStoreRetrievalIndex;
import io.github.wangyangxu.ailink.rag.InMemoryRetrievalIndex;
import io.github.wangyangxu.ailink.rag.KnowledgeVectorIndex;
import io.github.wangyangxu.ailink.rag.QdrantVectorStoreFactory;
import io.github.wangyangxu.ailink.rag.RetrievalIndex;
import io.github.wangyangxu.ailink.rag.VectorStoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 检索索引装配 —— 向量落哪、向量是否常驻 JVM 堆，都在这里收口。
 * <p>
 * <b>为什么默认进程内</b>：真实语料是「内置资产 + 用户上传」（千级片段），
 * 全量余弦扫描是微秒级，引入一个需要运维的向量库收益是负的。Qdrant 是<b>可选</b>provider：
 * 语料涨到十万级、堆内向量（10 万 × 1024 维 ≈ 400MB）开始挤压 JVM 时切过去，
 * 上层检索、融合、引用拼装一行都不用改。
 * <p>
 * <b>初始化失败怎么办</b>：降级为进程内索引并告警，而不是让应用起不来 ——
 * 向量库在本项目里的定位是「可以随时从 MySQL 全量重建」的可抛弃基础设施，
 * 不该成为启动单点。
 */
@Configuration
public class VectorStoreConfiguration {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreConfiguration.class);

    @Bean
    public RetrievalIndex retrievalIndex(VectorStoreProperties props) {
        String provider = props.getProvider();
        if (!"qdrant".equalsIgnoreCase(provider)) {
            if (!"in-memory".equalsIgnoreCase(provider)) {
                log.warn("未知的 vectorstore.provider={}，按 in-memory 处理", provider);
            }
            log.info("检索索引: 进程内（向量 + 关键词同在一份内存索引）");
            return new InMemoryRetrievalIndex(new KnowledgeVectorIndex(true));
        }
        try {
            QdrantVectorStoreFactory.Connection connection = QdrantVectorStoreFactory.open(props);
            log.info("检索索引: 向量 → Qdrant {}:{} collection={}（维度 {}，距离 Cosine），关键词 → 进程内 BM25",
                    props.getHost(), props.getPort(), props.getCollection(), props.getDimension());
            // 远端向量库模式下不再在堆内保留向量：省下的正是几百 MB 的浮点数组
            return new EmbeddingStoreRetrievalIndex(new KnowledgeVectorIndex(false),
                    connection.store(), connection.admin(), props);
        } catch (Throwable t) {
            log.warn("Qdrant 初始化失败，本次降级为进程内索引（关键词 + 向量都在堆内）: {}", t.toString());
            return new InMemoryRetrievalIndex(new KnowledgeVectorIndex(true));
        }
    }
}