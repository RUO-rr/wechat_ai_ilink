package io.github.wangyangxu.ailink.rag;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LangChain4j 原生切分器（{@code DocumentSplitters.recursive}）适配层的行为约束。
 * <p>
 * 这些断言的作用是把「框架 vs 自研」的对照锁在<b>切分策略</b>上：适配层不能改写内容、
 * 不能丢序号、不能超过配置上限 —— 否则评测里量到的差异就说不清是谁造成的。
 */
class Langchain4jTextSplitterTest {

    private static final int MAX = 300;
    private static final int OVERLAP = 50;

    private final Langchain4jTextSplitter splitter = new Langchain4jTextSplitter(MAX, OVERLAP);

    @Test
    void blankInputYieldsNothing() {
        assertTrue(splitter.split("   \n\n  ").isEmpty());
        assertTrue(splitter.split(null).isEmpty());
    }

    @Test
    void neverExceedsConfiguredChunkSize() {
        String text = ("这是一个很长的段落，" + "细节说明。".repeat(30) + "\n\n").repeat(6);

        List<TextChunker.Chunk> chunks = splitter.split(text);

        assertTrue(chunks.size() > 1, "长文档应切成多段");
        for (TextChunker.Chunk chunk : chunks) {
            assertTrue(chunk.text().length() <= MAX,
                    "片段长度应受 maxChars 约束，实际 " + chunk.text().length());
        }
    }

    @Test
    void chunkIndexIsSequentialAndContentKeepsSourceOrder() {
        String sentence = "大熊猫是中国的国宝，也是全球珍稀野生动物的旗舰物种。";
        String text = (sentence + "\n\n").repeat(20);

        List<TextChunker.Chunk> chunks = splitter.split(text);

        assertTrue(chunks.size() > 1);
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).index(), "片段序号必须连续（过短片段被丢弃后不能留空号）");
            assertTrue(chunks.get(i).text().length() >= 40, "过短片段应被丢弃");
        }
        assertTrue(chunks.get(0).text().contains("大熊猫是中国的国宝"), "首片应覆盖原文开头");
        assertTrue(chunks.get(chunks.size() - 1).text().contains("旗舰物种"),
                "末片应覆盖原文结尾 —— 切分不能吞内容");
    }

    @Test
    void frameworkSplitterHasNoHeadingPathWhileSelfBuiltKeepsIt() {
        String markdown = "# 简历写作规则\n\n" + "规则内容。".repeat(60)
                + "\n\n## ATS 兼容\n\n" + "关键字匹配说明。".repeat(60);
        TextChunker selfBuilt = new TextChunker(props(MAX, OVERLAP));

        List<TextChunker.Chunk> framework = splitter.split(markdown);
        List<TextChunker.Chunk> inHouse = selfBuilt.split(markdown);

        assertTrue(framework.size() >= 2 && inHouse.size() >= 2);
        assertTrue(framework.stream().allMatch(chunk -> chunk.heading() == null),
                "框架切分不认识 Markdown 标题，标题路径必然为空");
        assertTrue(inHouse.stream().anyMatch(chunk -> "简历写作规则 > ATS 兼容".equals(chunk.heading())),
                "自研切分器保留标题路径（引用定位要用）");
        assertNull(framework.get(0).heading());
    }

    private static RagProperties props(int maxChars, int overlap) {
        RagProperties props = new RagProperties();
        ReflectionTestUtils.setField(props, "chunkMaxChars", maxChars);
        ReflectionTestUtils.setField(props, "chunkOverlapChars", overlap);
        return props;
    }
}
