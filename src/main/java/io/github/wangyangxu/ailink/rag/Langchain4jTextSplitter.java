package io.github.wangyangxu.ailink.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;

/**
 * LangChain4j 原生切分器适配 —— {@code DocumentSplitters.recursive(maxChars, overlap)}。
 * <p>
 * 与自研 {@link TextChunker} 的差别（正是对照评测要量的东西）：
 * <ul>
 *   <li>框架的递归拆分按「段落 → 句子 → 词 → 字符」逐级降级，<b>不看 Markdown 标题</b>，
 *       所以片段没有标题路径，引用只能定位到「文档 + 片段序号」；</li>
 *   <li>换来的是零维护，而且与框架其它组件（{@code EmbeddingStoreIngestor}）天然同源：
 *       切分参数与重叠语义由框架保证，不占自己的代码。</li>
 * </ul>
 * 适配层只做两件事：把 {@code String} 包成 {@code Document}、把框架产出的 {@code TextSegment}
 * 映射回链路内的 {@link TextChunker.Chunk}。这里不掺任何自己的切分逻辑 ——
 * 否则「框架 vs 自研」的对照就成了「框架+自研 vs 自研」，结论不作数。
 */
public class Langchain4jTextSplitter implements TextSplitter {

    /** 与自研切分器同一道门槛（{@code TextChunker.MIN_CHUNK_CHARS}）：过短片段会被检索『命中』但没用 */
    private static final int MIN_CHUNK_CHARS = 40;

    private final DocumentSplitter delegate;

    public Langchain4jTextSplitter(RagProperties props) {
        this(props.getChunkMaxChars(), props.getChunkOverlapChars());
    }

    public Langchain4jTextSplitter(int maxChars, int overlapChars) {
        this.delegate = DocumentSplitters.recursive(maxChars, overlapChars);
    }

    @Override
    public List<TextChunker.Chunk> split(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return List.of();
        }
        Document document = Document.from(rawText.replace("\r\n", "\n").replace('\r', '\n'));
        List<TextChunker.Chunk> chunks = new ArrayList<>();
        int index = 0;
        for (TextSegment segment : delegate.split(document)) {
            String text = segment.text().strip();
            if (text.length() < MIN_CHUNK_CHARS) {
                continue;
            }
            // heading 恒为 null：框架切分不认识 Markdown 标题，这一点在报告里如实体现，不猜也不补
            chunks.add(new TextChunker.Chunk(index++, null, text));
        }
        return chunks;
    }
}
