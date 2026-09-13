package io.github.wangyangxu.ailink.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * 轻量分词器 —— 中文按「字符二元组（bigram）」切分，英文/数字按词切分。
 * <p>
 * <b>为什么不引入分词模型</b>：本项目的检索语料是中文技术文档 + 英文术语，
 * bigram 召回对「简历 / 模板 / ATS」这类固定搭配已经够用，且零依赖、零启动开销。
 * 代价是丢掉了词级语义（"招聘" 与 "招人" 不共享 token），
 * 语义召回交给向量通道承担，两条通道互补 —— 这正是混合检索的动机。
 */
public final class Tokenizers {

    private Tokenizers() {}

    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        StringBuilder asciiRun = new StringBuilder();
        StringBuilder cjkRun = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                flushAscii(asciiRun, tokens);
                cjkRun.append(c);
            } else if (isWordChar(c)) {
                flushCjk(cjkRun, tokens);
                asciiRun.append(Character.toLowerCase(c));
            } else {
                flushAscii(asciiRun, tokens);
                flushCjk(cjkRun, tokens);
            }
        }
        flushAscii(asciiRun, tokens);
        flushCjk(cjkRun, tokens);
        return tokens;
    }

    private static void flushAscii(StringBuilder run, List<String> tokens) {
        if (run.length() > 0) {
            tokens.add(run.toString());
            run.setLength(0);
        }
    }

    private static void flushCjk(StringBuilder run, List<String> tokens) {
        int n = run.length();
        if (n == 0) {
            return;
        }
        if (n == 1) {
            tokens.add(run.toString());
        } else {
            for (int i = 0; i + 1 < n; i++) {
                tokens.add(run.substring(i, i + 2));
            }
        }
        run.setLength(0);
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)      // 基本汉字
                || (c >= 0x3400 && c <= 0x4DBF)  // 扩展 A
                || (c >= 0xF900 && c <= 0xFAFF); // 兼容汉字
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) && !isCjk(c);
    }
}