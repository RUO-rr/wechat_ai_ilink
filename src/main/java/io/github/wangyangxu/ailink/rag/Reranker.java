package io.github.wangyangxu.ailink.rag;

import java.util.List;

/**
 * 精排接口 —— 召回（向量 + 关键词）之后的重排序阶段。
 * <p>
 * 召回看的是「像不像」，精排看的是「答不答得上」：交叉编码器逐对打分，精度高但慢，
 * 所以只作用在召回后的少量候选上。返回 null 表示本次不做精排（未配置或调用失败），
 * 调用方按召回分排序，链路照常出结果。
 */
@FunctionalInterface
public interface Reranker {

    /** @return 与 candidates 等长的相关性分数；null 表示跳过精排 */
    List<Double> score(String query, List<String> candidates);

    static Reranker noop() {
        return (query, candidates) -> null;
    }
}