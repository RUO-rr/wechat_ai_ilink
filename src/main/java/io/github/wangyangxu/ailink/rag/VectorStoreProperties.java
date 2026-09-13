package io.github.wangyangxu.ailink.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 向量通道配置（application.properties 的 {@code vectorstore.*}）。
 * <p>
 * 默认 {@code in-memory}：向量与关键词共用同一份内存索引（千级片段的最优解，零外部依赖）。
 * 切到 {@code qdrant}：向量落到 Qdrant 的 HNSW 索引，JVM 堆里只保留关键词通道 ——
 * BM25 依赖全文，文本本来就要驻留内存；真正省下来的是向量
 * （10 万片段 × 1024 维 × 4B ≈ 400MB，才是内存的大头）。
 * <p>
 * 用 @Value 与项目其余配置读取方式保持一致。
 */
@Component
public class VectorStoreProperties {

    /** 向量通道落点：in-memory（默认）| qdrant */
    @Value("${vectorstore.provider:in-memory}")
    private String provider;

    @Value("${vectorstore.qdrant.host:127.0.0.1}")
    private String host;

    @Value("${vectorstore.qdrant.port:6334}")
    private int port;

    @Value("${vectorstore.qdrant.collection:ai_ilink_knowledge}")
    private String collection;

    @Value("${vectorstore.qdrant.api-key:}")
    private String apiKey;

    @Value("${vectorstore.qdrant.use-tls:false}")
    private boolean useTls;

    /** 建集合用的向量维度：与向量模型输出维度一致（换模型要换集合名或删集合重建）。 */
    @Value("${vectorstore.qdrant.dimension:1024}")
    private int dimension;

    /** 启动时若集合为空，是否用库内已存向量回灌（幂等 id，重复启动不会产生重复点）。 */
    @Value("${vectorstore.qdrant.rebuild-on-boot:true}")
    private boolean rebuildOnBoot;

    /** 单次远端调用超时；超时按「不可用」处理，走关键词降级。 */
    @Value("${vectorstore.qdrant.request-timeout-ms:3000}")
    private long requestTimeoutMs;

    /** 远端不可用后的重试间隔：避免每个查询都去撞一台挂掉的服务器。 */
    @Value("${vectorstore.qdrant.retry-backoff-ms:30000}")
    private long retryBackoffMs;

    /** payload 中存放原文的键名：与 LangChain4j 的 Qdrant 实现默认值保持一致。 */
    @Value("${vectorstore.qdrant.payload-text-key:text_segment}")
    private String payloadTextKey;

    /** 是否使用远端向量库 —— 同时决定「向量是否常驻 JVM 堆」。 */
    public boolean isRemote() {
        return "qdrant".equalsIgnoreCase(getProvider());
    }

    public String getProvider() {
        return provider == null || provider.isBlank() ? "in-memory" : provider.trim();
    }

    public String getHost() { return host; }
    public int getPort() { return port; }
    public String getCollection() { return collection; }
    public String getApiKey() { return apiKey; }
    public boolean isUseTls() { return useTls; }
    public int getDimension() { return dimension; }
    public boolean isRebuildOnBoot() { return rebuildOnBoot; }
    public long getRequestTimeoutMs() { return requestTimeoutMs; }
    public long getRetryBackoffMs() { return retryBackoffMs; }

    public String getPayloadTextKey() {
        // 键名不能为空：LangChain4j 会把它直接放进 payload，null 会在组包时抛 NPE
        return payloadTextKey == null || payloadTextKey.isBlank() ? "text_segment" : payloadTextKey;
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}