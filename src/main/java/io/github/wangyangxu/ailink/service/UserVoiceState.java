package io.github.wangyangxu.ailink.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户音色状态管理器。
 * 每个用户可以独立设置 TTS 音色，默认使用 "longanhuan"（龙安欢 女声）。
 * <p>
 * 音色列表来源于阿里百炼 CosyVoice v3 真实 API 文档。
 * 注意：不同 model 支持不同的音色组，混用会报 418。
 */
@Service
public class UserVoiceState {

    private static final Logger log = LoggerFactory.getLogger(UserVoiceState.class);

    /** 默认音色 */
    public static final String DEFAULT_VOICE = "longanhuan";

    /** 用户ID → 音色ID 的映射 */
    private final ConcurrentHashMap<String, String> userVoices = new ConcurrentHashMap<>();

    /**
     * 阿里百炼 CosyVoice v3 真实支持的音色。
     * key=中文描述，value=API voice 参数值（注意部分带 _v3 后缀）。
     * 来源：DashScope CosyVoice 官方 API 文档。
     */
    public static final Map<String, String> SUPPORTED_VOICES = new LinkedHashMap<>();

    static {
        // ===== 社交陪伴 =====
        SUPPORTED_VOICES.put("龙安欢（女·元气）",     "longanhuan");
        SUPPORTED_VOICES.put("龙安洋（男·阳光）",     "longanyang");
        SUPPORTED_VOICES.put("龙华（女·甜美）",       "longhua_v3");
        SUPPORTED_VOICES.put("龙婉（女·柔声）",       "longwan_v3");
        SUPPORTED_VOICES.put("龙星（女·温婉）",       "longxing_v3");
        SUPPORTED_VOICES.put("龙橙（男·智慧青年）",    "longcheng_v3");
        SUPPORTED_VOICES.put("龙泽（男·温暖）",       "longze_v3");
        SUPPORTED_VOICES.put("龙安柔（女·闺蜜）",     "longanrou_v3");

        // ===== 童声 =====
        SUPPORTED_VOICES.put("龙呼呼（女童·天真）",    "longhuhu_v3");
        SUPPORTED_VOICES.put("龙泡泡（童声·可爱）",    "longpaopao_v3");
        SUPPORTED_VOICES.put("龙闪闪（童声·戏剧）",    "longshanshan_v3");

        // ===== 方言 =====
        SUPPORTED_VOICES.put("龙嘉欣（粤语女）",       "longjiaxin_v3");
        SUPPORTED_VOICES.put("龙老铁（东北话）",       "longlaotie_v3");
        SUPPORTED_VOICES.put("龙陕哥（陕西话）",       "longshange_v3");

        // ===== 外语 =====
        SUPPORTED_VOICES.put("Bella（美式英语女）",    "loongbella_v3");
        SUPPORTED_VOICES.put("Abby（美式英语女）",     "loongabby_v3");
        SUPPORTED_VOICES.put("Emily（英式英语女）",    "loongemily_v3");
        SUPPORTED_VOICES.put("Riko（日语女）",         "loongriko_v3");
        SUPPORTED_VOICES.put("Kyong（韩语女）",        "loongkyong_v3");

        // ===== 语音助手 =====
        SUPPORTED_VOICES.put("龙小淳（助手·中英）",    "longxiaochun_v3");
        SUPPORTED_VOICES.put("龙小夏（助手·中文）",    "longxiaoxia_v3");

        // ===== 有声书/播报 =====
        SUPPORTED_VOICES.put("龙妙（有声书）",         "longmiao_v3");
        SUPPORTED_VOICES.put("龙三叔（有声书男）",     "longsanshu_v3");
        SUPPORTED_VOICES.put("龙硕（新闻播报男）",     "longshuo_v3");
    }

    /** 所有有效的音色ID（API 参数值） */
    public static final Set<String> SUPPORTED_VOICE_IDS = Set.copyOf(SUPPORTED_VOICES.values());

    /**
     * 获取用户当前设置的音色。
     */
    public String getVoice(String userId) {
        return userVoices.getOrDefault(userId, DEFAULT_VOICE);
    }

    /**
     * 设置用户的音色。
     */
    public boolean setVoice(String userId, String voiceId) {
        if (voiceId == null || !SUPPORTED_VOICE_IDS.contains(voiceId)) {
            log.warn("不支持的音色: {}", voiceId);
            return false;
        }
        userVoices.put(userId, voiceId);
        log.info("用户 {} 的音色已切换为 {}", userId, voiceId);
        return true;
    }

    /**
     * 获取支持的中文描述列表（用于展示给用户）。
     */
    public List<String> getSupportedVoiceNames() {
        return new ArrayList<>(SUPPORTED_VOICES.keySet());
    }

    /**
     * 通过用户输入的文字模糊匹配音色 ID。
     * 先匹配中文名，再匹配英文 ID。
     */
    public String findVoiceId(String userInput) {
        if (userInput == null) return null;
        String trimmed = userInput.trim();

        // 先精准匹配英文 ID
        if (SUPPORTED_VOICE_IDS.contains(trimmed)) {
            return trimmed;
        }

        // 再模糊匹配中文描述（看用户输入包含了哪个 key）
        for (Map.Entry<String, String> entry : SUPPORTED_VOICES.entrySet()) {
            if (trimmed.contains(entry.getKey().substring(0, Math.min(3, entry.getKey().length())))) {
                return entry.getValue();
            }
        }

        // 最后看中文描述里是否包含用户输入
        for (Map.Entry<String, String> entry : SUPPORTED_VOICES.entrySet()) {
            if (entry.getKey().contains(trimmed)) {
                return entry.getValue();
            }
        }

        return null;
    }

    /**
     * 清除用户的音色设置，恢复默认。
     */
    public void resetVoice(String userId) {
        userVoices.remove(userId);
        log.info("用户 {} 的音色已重置为默认", userId);
    }
}