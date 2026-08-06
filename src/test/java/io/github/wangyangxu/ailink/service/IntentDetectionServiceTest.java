package io.github.wangyangxu.ailink.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntentDetectionServiceTest {

    @Mock
    private UserVoiceState userVoiceState;

    @InjectMocks
    private IntentDetectionService service;

    @Test
    void strongDrawKeyword_routesToDraw() {
        assertEquals("1", service.detect("u1", "帮我画一只猫"));
        assertEquals("1", service.detect("u1", "生成图片：日落"));
        assertEquals("1", service.detect("u1", "画一张山水画"));
    }

    @Test
    void weakDrawKeyword_fallsThroughToText() {
        // 含"画"字但不是明确画图意图 → 交给 FC/LLM 判断，不硬路由
        assertEquals("2", service.detect("u1", "我想画个流程图"));
    }

    @Test
    void voiceKeyword_routesToVoice() {
        assertEquals("3", service.detect("u1", "用语音回复我"));
        assertEquals("3", service.detect("u1", "语音回答一下"));
    }

    @Test
    void voiceSwitch_resolvableName_routesToSwitch() {
        when(userVoiceState.findVoiceId("龙安欢")).thenReturn("longanhuan");
        assertEquals("4:龙安欢", service.detect("u1", "切换音色为龙安欢"));
    }

    @Test
    void voiceSwitch_unknownName_fallsThroughToText() {
        when(userVoiceState.findVoiceId("不存在的音色")).thenReturn(null);
        assertEquals("2", service.detect("u1", "切换音色为不存在的音色"));
    }

    @Test
    void tableKeyword_routesToText() {
        assertEquals("2", service.detect("u1", "帮我做个表格"));
    }
}
