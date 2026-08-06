package io.github.wangyangxu.ailink.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 意图检测服务 —— 纯强信号关键词硬路由，零 LLM 调用。
 * <p>
 * 设计说明：独立意图检测的 LLM 调用已并入 FC 循环（意图协议），
 * 这里只保留"强信号才路由"的兜底前置：明确的关键词直接分流到
 * 画图/语音/切换音色，弱信号或不确定的一律返回 "2" 交给 FC/LLM 判断，
 * 避免"我想画个流程图"这类含"画"字的文本需求被误判。
 */
@Service
public class IntentDetectionService {

    private static final Logger log = LoggerFactory.getLogger(IntentDetectionService.class);

    /** 强信号画图关键词（保守口径，避免误判） */
    private static final List<String> DRAW_SIGNALS = List.of(
            "画一张", "画一幅", "画一只", "帮我画", "帮我生成", "生成图片", "画图", "绘制");

    /** 强信号语音回复关键词 */
    private static final List<String> VOICE_SIGNALS = List.of(
            "用语音回复", "语音回答", "语音回复我", "用语音说", "语音告诉我");

    /** 强信号音色切换关键词 */
    private static final List<String> VOICE_SWITCH_SIGNALS = List.of(
            "切换音色", "换音色", "切换成", "换成");

    private final UserVoiceState userVoiceState;

    public IntentDetectionService(UserVoiceState userVoiceState) {
        this.userVoiceState = userVoiceState;
    }

    /**
     * @return "1"=画图, "2"=文字回复(默认), "3"=语音回复, "4:音色名"=切换音色
     */
    public String detect(String userId, String userMessage) {
        String trimmed = userMessage == null ? "" : userMessage.trim();

        // 音色切换：命中关键词且能解析出已知音色名才路由
        String voiceName = detectVoiceSwitch(trimmed);
        if (voiceName != null) {
            String resolved = userVoiceState.findVoiceId(voiceName);
            if (resolved != null) {
                log.info("音色切换硬路由: voiceName={}, resolved={}", voiceName, resolved);
                return "4:" + voiceName;
            }
        }

        if (containsAny(trimmed, VOICE_SIGNALS)) {
            log.info("语音回复硬路由");
            return "3";
        }

        if (containsAny(trimmed, DRAW_SIGNALS)) {
            log.info("画图硬路由");
            return "1";
        }

        // 表格/Excel 类任务直接走文字回复（FC），避免误判
        if (trimmed.contains("表格") || trimmed.contains("excel") || trimmed.contains("xlsx")
                || trimmed.contains("电子表格") || trimmed.contains("数据表")) {
            return "2";
        }

        return "2";
    }

    /**
     * 从消息中提取音色名：优先匹配"切换音色为X / 换音色为X / 切换成X / 换成X"。
     * 提取失败返回 null（不路由，交给 FC）。
     */
    private static String detectVoiceSwitch(String text) {
        for (String signal : VOICE_SWITCH_SIGNALS) {
            int idx = text.indexOf(signal);
            if (idx < 0) continue;
            String after = text.substring(idx + signal.length()).trim();
            // 去掉常见的前置词（为/成/到/用）
            after = after.replaceFirst("^(为|成|到|用)", "").trim();
            if (!after.isEmpty()) {
                return after;
            }
        }
        return null;
    }

    private static boolean containsAny(String text, List<String> signals) {
        for (String s : signals) {
            if (text.contains(s)) return true;
        }
        return false;
    }
}
