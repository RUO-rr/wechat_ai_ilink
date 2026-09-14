package io.github.wangyangxu.ailink.rag;

import java.util.List;

/**
 * 文档切分端口 —— RAG 链路的第一段，把「怎么切」从索引链路里抽出来。
 * <p>
 * 为什么值得抽这个口子：切分是检索质量最敏感的一环（片段太长会稀释语义、太短会把答案切散），
 * 而它有两类完全不同的实现思路，需要拿同一套语料对量：
 * <ul>
 *   <li>{@link TextChunker}（默认，自研）：<b>标题感知</b> —— 先按 Markdown 标题切成小节，
 *       再按「段落 → 句子 → 硬切」逐级降级，并保留标题路径用于引用定位；</li>
 *   <li>{@link Langchain4jTextSplitter}（可选，{@code rag.splitter=langchain4j}）：直接复用
 *       LangChain4j 的 {@code DocumentSplitters.recursive(maxChars, overlap)}，框架原生、零维护，
 *       但不理解 Markdown 标题，片段拿不到标题路径。</li>
 * </ul>
 * 两者产出同一个 DTO（{@link TextChunker.Chunk}），所以索引、检索、融合、引用拼装都不需要知道
 * 用的是谁 —— 这也是能用同一套题库（{@code RagEvaluationTest}）对两者跑对照的前提。
 * <p>
 * 注：{@code Chunk} 这个 DTO 留在 {@link TextChunker} 里没有搬走，是因为它已被索引链路与多个测试引用，
 * 为了让一个 record 换个位置而改动整条链路的 diff 没有价值。
 */
public interface TextSplitter {

    /** 切成片段；入参为空时返回空列表，不返回 {@code null}。 */
    List<TextChunker.Chunk> split(String rawText);
}
