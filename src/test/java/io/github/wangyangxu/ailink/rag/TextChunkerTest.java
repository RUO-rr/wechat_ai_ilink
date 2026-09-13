package io.github.wangyangxu.ailink.rag;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextChunkerTest {

    private TextChunker chunker(int maxChars, int overlap) {
        RagProperties props = new RagProperties();
        ReflectionTestUtils.setField(props, "chunkMaxChars", maxChars);
        ReflectionTestUtils.setField(props, "chunkOverlapChars", overlap);
        return new TextChunker(props);
    }

    @Test
    void splitsByMarkdownHeadingsAndKeepsHeadingPath() {
        String markdown = "# 简历写作规则\n\n" + "规则内容。".repeat(60)
                + "\n\n## ATS 兼容\n\n" + "关键字匹配说明。".repeat(60);

        List<TextChunker.Chunk> chunks = chunker(300, 50).split(markdown);

        assertTrue(chunks.size() >= 2, "长文档应切成多段");
        assertTrue(chunks.stream().anyMatch(c -> "简历写作规则".equals(c.heading())));
        assertTrue(chunks.stream().anyMatch(c -> "简历写作规则 > ATS 兼容".equals(c.heading())));
    }

    @Test
    void chunkIndexIsSequentialAndContentNonEmpty() {
        List<TextChunker.Chunk> chunks = chunker(200, 40).split("段落一。".repeat(80));

        assertTrue(chunks.size() > 1);
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).index());
            assertTrue(chunks.get(i).text().length() >= 40, "过短片段应被丢弃");
        }
    }

    @Test
    void neverExceedsConfiguredChunkSize() {
        int max = 300;
        String text = ("这是一个很长的段落，" + "细节说明。".repeat(30) + "\n\n").repeat(6);

        for (TextChunker.Chunk chunk : chunker(max, 60).split(text)) {
            assertTrue(chunk.text().length() <= max,
                    "片段长度应受 maxChars 约束，实际 " + chunk.text().length());
        }
    }

    @Test
    void plainTextWithoutHeadingsStillChunks() {
        List<TextChunker.Chunk> chunks = chunker(200, 0).split("没有任何标题的纯文本内容。".repeat(50));

        assertTrue(!chunks.isEmpty());
        assertNull(chunks.get(0).heading());
    }

    @Test
    void blankInputYieldsNothing() {
        assertTrue(chunker(300, 50).split("   \n\n  ").isEmpty());
        assertTrue(chunker(300, 50).split(null).isEmpty());
    }
}