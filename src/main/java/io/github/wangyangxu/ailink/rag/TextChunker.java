package io.github.wangyangxu.ailink.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 标题感知的文档切分器 —— RAG 链路的第一段。
 * <p>
 * 两段式切分：
 * <ol>
 *   <li><b>语义段</b>：按 Markdown 标题（# 层级）切成小节，并记录「标题路径」，
 *       检索命中时能给出可核对的引用位置（例如 {@code 三、代码质量改进 > 3.2 测试}）；</li>
 *   <li><b>片段</b>：小节超过 maxChars 时，按「段落 → 句子 → 硬切」逐级降级拆分，
 *       相邻片段保留 overlapChars 重叠，避免答案正好落在切口上。</li>
 * </ol>
 * 纯文本（没有标题）会退化成单小节切分，行为一致。
 */
@Component
public class TextChunker {

    /** 切分结果：index 为该文档内的片段序号，heading 为标题路径（可为 null） */
    public record Chunk(int index, String heading, String text) {}

    private static final int MIN_CHUNK_CHARS = 40;

    private final RagProperties props;

    public TextChunker(RagProperties props) {
        this.props = props;
    }

    public List<Chunk> split(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return List.of();
        }
        String text = rawText.replace("\r\n", "\n").replace('\r', '\n');
        List<Section> sections = splitIntoSections(text);

        List<Chunk> chunks = new ArrayList<>();
        int index = 0;
        for (Section section : sections) {
            for (String piece : splitSection(section.text())) {
                if (piece.isBlank() || piece.length() < MIN_CHUNK_CHARS) {
                    // 过短的片段（如只有一行小标题）直接丢弃，避免污染检索结果
                    continue;
                }
                chunks.add(new Chunk(index++, section.heading(), piece));
            }
        }
        return chunks;
    }

    private record Section(String heading, String text) {}

    /** 按 Markdown 标题切小节，同时维护标题栈拼出标题路径。 */
    private List<Section> splitIntoSections(String text) {
        List<Section> sections = new ArrayList<>();
        Deque<String> headingStack = new ArrayDeque<>();
        StringBuilder buffer = new StringBuilder();
        String currentHeading = null;

        for (String line : text.split("\n", -1)) {
            int level = headingLevel(line);
            if (level > 0) {
                if (!buffer.isEmpty()) {
                    sections.add(new Section(currentHeading, buffer.toString()));
                    buffer.setLength(0);
                }
                while (headingStack.size() >= level) {
                    headingStack.pollLast();
                }
                headingStack.addLast(line.substring(line.indexOf(' ') + 1).trim());
                currentHeading = String.join(" > ", headingStack);
                // 标题本身也进入正文，保证标题词可被检索命中
                buffer.append(line.trim()).append('\n');
            } else {
                buffer.append(line).append('\n');
            }
        }
        if (!buffer.isEmpty()) {
            sections.add(new Section(currentHeading, buffer.toString()));
        }
        return sections;
    }

    private static int headingLevel(String line) {
        String trimmed = line.stripLeading();
        if (!trimmed.startsWith("#")) {
            return 0;
        }
        int level = 0;
        while (level < trimmed.length() && trimmed.charAt(level) == '#' && level < 6) {
            level++;
        }
        return (level > 0 && level < trimmed.length() && trimmed.charAt(level) == ' ') ? level : 0;
    }

    /** 段落优先，超长再降级到句子，最终硬切并带重叠。 */
    private List<String> splitSection(String sectionText) {
        int max = Math.max(200, props.getChunkMaxChars());
        int overlap = Math.min(Math.max(0, props.getChunkOverlapChars()), max / 2);

        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : sectionText.split("\n\\s*\n")) {
            String p = paragraph.strip();
            if (p.isEmpty()) {
                continue;
            }
            if (p.length() > max) {
                if (!current.isEmpty()) {
                    pieces.add(current.toString().strip());
                    current.setLength(0);
                }
                pieces.addAll(splitLongParagraph(p, max, overlap));
                continue;
            }
            if (current.length() + p.length() + 1 > max && !current.isEmpty()) {
                String finished = current.toString().strip();
                pieces.add(finished);
                current.setLength(0);
                if (overlap > 0) {
                    current.append(tail(finished, overlap)).append('\n');
                }
            }
            current.append(p).append('\n');
        }
        if (!current.isEmpty()) {
            pieces.add(current.toString().strip());
        }
        return pieces;
    }

    private List<String> splitLongParagraph(String paragraph, int max, int overlap) {
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : paragraph.split("(?<=[。！？；!?;])")) {
            if (sentence.length() > max) {
                if (!current.isEmpty()) {
                    pieces.add(current.toString().strip());
                    current.setLength(0);
                }
                for (int start = 0; start < sentence.length(); start += max - overlap) {
                    int end = Math.min(sentence.length(), start + max);
                    pieces.add(sentence.substring(start, end).strip());
                    if (end == sentence.length()) {
                        break;
                    }
                }
                continue;
            }
            if (current.length() + sentence.length() > max && !current.isEmpty()) {
                String finished = current.toString().strip();
                pieces.add(finished);
                current.setLength(0);
                if (overlap > 0) {
                    current.append(tail(finished, overlap));
                }
            }
            current.append(sentence);
        }
        if (!current.isEmpty()) {
            pieces.add(current.toString().strip());
        }
        return pieces;
    }

    private static String tail(String text, int n) {
        return text.length() <= n ? text : text.substring(text.length() - n);
    }
}