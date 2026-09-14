package io.github.wangyangxu.ailink.config;

import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.community.model.dashscope.QwenScoringModel;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.github.wangyangxu.ailink.rag.HashingEmbeddingModel;
import io.github.wangyangxu.ailink.rag.Langchain4jTextSplitter;
import io.github.wangyangxu.ailink.rag.RagProperties;
import io.github.wangyangxu.ailink.rag.Reranker;
import io.github.wangyangxu.ailink.rag.TextChunker;
import io.github.wangyangxu.ailink.rag.TextSplitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * RAG 链路装配 —— 向量模型与精排模型的选择、降级都在这里收口。
 * <p>
 * 降级策略（保证「没配密钥 / 模型不可用」时链路仍然可用，而不是整块功能不可用）：
 * <ul>
 *   <li>有 DashScope 密钥 → 真实语义向量（text-embedding-v4）；</li>
 *   <li>无密钥 / 初始化失败 → 本地词法哈希向量，只做字面召回；</li>
 *   <li>精排默认关闭，开启后若 SDK 版本或模型不支持，自动退回不精排。</li>
 * </ul>
 */
@Configuration
public class RagConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RagConfiguration.class);

    /**
     * 切分器装配 —— 自研与框架实现二选一（{@code rag.splitter}），缺省是自研的标题感知切分。
     * <p>
     * 两个实现的产出是同一个 DTO，切换只在启动时发生一次，检索、融合、引用拼装都不用改；
     * 选型依据见 {@code docs/bench/rag-eval.md} 的「切分器对照」与 D-18。
     */
    @Bean
    public TextSplitter ragTextSplitter(RagProperties props) {
        String provider = props.getSplitter();
        if ("langchain4j".equalsIgnoreCase(provider)) {
            log.info("RAG 切分器: LangChain4j DocumentSplitters.recursive(maxChars={}, overlap={})，片段不带标题路径",
                    props.getChunkMaxChars(), props.getChunkOverlapChars());
            return new Langchain4jTextSplitter(props);
        }
        if (provider != null && !provider.isBlank() && !"self".equalsIgnoreCase(provider)) {
            log.warn("未知的 rag.splitter={}，按 self 处理", provider);
        }
        log.info("RAG 切分器: 自研标题感知切分（maxChars={} / overlap={}），片段带标题路径",
                props.getChunkMaxChars(), props.getChunkOverlapChars());
        return new TextChunker(props);
    }

    @Bean
    public EmbeddingModel ragEmbeddingModel(RagProperties props) {
        String apiKey = props.getEmbeddingApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            try {
                QwenEmbeddingModel.QwenEmbeddingModelBuilder builder = QwenEmbeddingModel.builder()
                        .apiKey(apiKey.trim())
                        .modelName(props.getEmbeddingModel())
                        .dimension(props.getEmbeddingDimension());
                if (props.getEmbeddingBaseUrl() != null && !props.getEmbeddingBaseUrl().isBlank()) {
                    builder.baseUrl(props.getEmbeddingBaseUrl().trim());
                }
                EmbeddingModel model = builder.build();
                log.info("RAG 向量模型就绪: DashScope {} (dimension={})",
                        props.getEmbeddingModel(), props.getEmbeddingDimension());
                return model;
            } catch (Throwable t) {
                log.warn("DashScope 向量模型初始化失败，降级为本地词法向量: {}", t.toString());
            }
        } else {
            log.warn("未配置 rag.embedding.api-key，RAG 使用本地词法哈希向量（仅字面召回，语义泛化能力受限）");
        }
        return new HashingEmbeddingModel(props.getFallbackDimension());
    }

    @Bean
    public Reranker ragReranker(RagProperties props) {
        if (!props.isRerankEnabled()) {
            return Reranker.noop();
        }
        String apiKey = props.getEmbeddingApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("已开启精排但未配置密钥，自动退回不精排");
            return Reranker.noop();
        }
        try {
            QwenScoringModel.QwenScoringModelBuilder builder = QwenScoringModel.builder()
                    .apiKey(apiKey.trim())
                    .modelName(props.getRerankModel());
            if (props.getEmbeddingBaseUrl() != null && !props.getEmbeddingBaseUrl().isBlank()) {
                builder.baseUrl(props.getEmbeddingBaseUrl().trim());
            }
            QwenScoringModel model = builder.build();
            log.info("RAG 精排模型就绪: DashScope {}", props.getRerankModel());
            return (query, candidates) -> {
                if (candidates == null || candidates.isEmpty()) {
                    return null;
                }
                Response<List<Double>> response =
                        model.scoreAll(candidates.stream().map(TextSegment::from).toList(), query);
                return response.content();
            };
        } catch (Throwable t) {
            log.warn("精排模型不可用（需 dashscope-sdk-java >= 2.22），自动退回不精排: {}", t.toString());
            return Reranker.noop();
        }
    }
}
