package com.seeky.airuntime.service.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seeky.airuntime.common.BusinessException;
import com.seeky.airuntime.common.ErrorCode;
import com.seeky.airuntime.config.AsrProperties;
import com.seeky.airuntime.config.EffectiveSettings;
import com.seeky.airuntime.config.ModelProperties;
import com.seeky.airuntime.config.RuntimeSettings;
import com.seeky.airuntime.dto.Transcript;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 语音识别的协议分发与响应解析。
 *
 * <p>真调模型的部分不在这里（那需要 Key 和网络，属于联调），
 * 这里守住的是"选错协议 / 没配 / 空音频 / 模型乱回话"这些分支不会悄悄走到发假文本。
 */
class AsrServiceTest {

    private static ModelProperties.Endpoint endpoint(boolean enabled, String protocol,
            String apiKey, String model) {
        return new ModelProperties.Endpoint(enabled, protocol,
                "https://ark.example.com/api/plan/v3", apiKey, model, "");
    }

    private static EffectiveSettings settingsWith(ModelProperties.Endpoint asr) {
        ModelProperties models = new ModelProperties(
                endpoint(true, "openai", "llm-key", "some-llm"),
                asr,
                endpoint(true, "volcengine", "tts-key", "some-tts"),
                endpoint(false, "mock", "", ""),
                endpoint(false, "mock", "", ""));
        return new EffectiveSettings(models, null);
    }

    private static AsrService service() {
        EffectiveSettings defaults = settingsWith(endpoint(true, "ark", "k", "m"));
        RuntimeSettings runtime = new RuntimeSettings(defaults.models(), null);
        return new AsrService(runtime, new AsrProperties("volc.bigasr.auc_turbo", "test", Duration.ofSeconds(5)));
    }

    // ------------------------------------------------------------------ 协议分发

    @Test
    @DisplayName("mock 协议返回固定文本，并且标了 mock=true（前端据此拒绝发送）")
    void mockReportsItselfAsMock() {
        Transcript t = service().transcribe("QUJD", "wav", 1200L,
                settingsWith(endpoint(true, "mock", "", ""))).block();

        assertThat(t).isNotNull();
        assertThat(t.mock()).isTrue();
        assertThat(t.text()).isNotBlank();
        assertThat(t.model()).isEqualTo("mock");
    }

    @Test
    @DisplayName("ASR_ENABLED=false 时明确说被禁用，不是静默返回空")
    void disabledIsExplicit() {
        assertThatThrownBy(() -> service().transcribe("QUJD", "wav", 1200L,
                settingsWith(endpoint(false, "ark", "k", "m"))).block())
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.ASR_ERROR);
    }

    @Test
    @DisplayName("ark 协议缺 Key 时提示还没配置，并说出缺什么")
    void arkWithoutKeyExplainsWhatIsMissing() {
        assertThatThrownBy(() -> service().transcribe("QUJD", "wav", 1200L,
                settingsWith(endpoint(true, "ark", "", "doubao-seed-2-1-lite-260915"))).block())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("还没配置")
                .hasMessageContaining("apiKey");
    }

    @Test
    @DisplayName("空音频在参数层就挡掉，不发网络请求")
    void rejectsEmptyAudio() {
        assertThatThrownBy(() -> service().transcribe("  ", "wav", 1200L,
                settingsWith(endpoint(true, "ark", "k", "m"))).block())
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_MISSING);
    }

    @Test
    @DisplayName("未知协议直接报错，不会拿个 openai 协议去猜")
    void rejectsUnknownProtocol() {
        assertThatThrownBy(() -> service().transcribe("QUJD", "wav", 1200L,
                settingsWith(endpoint(true, "openai", "k", "m"))).block())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的 ASR 协议")
                .hasMessageContaining("ark");
    }

    // ------------------------------------------------------------------ 响应解析

    @Test
    @DisplayName("方舟的 choices[0].message.content 字符串能取出来")
    void parsesArkStringContent() {
        String raw = """
                {"choices":[{"message":{"role":"assistant","content":"感冒了怎么办"}}]}""";

        assertThat(AsrService.arkTextOf(raw)).isEqualTo("感冒了怎么办");
    }

    @Test
    @DisplayName("content 是内容块数组时也能拼出文字")
    void parsesArkBlockContent() {
        String raw = """
                {"choices":[{"message":{"content":[
                  {"type":"text","text":"今天"},
                  {"type":"text","text":"有点冷"}
                ]}}]}""";

        assertThat(AsrService.arkTextOf(raw)).isEqualTo("今天有点冷");
    }

    @Test
    @DisplayName("乱码 / 空响应 / 缺字段一律返回空串，不抛异常")
    void parsesGarbageQuietly() {
        assertThat(AsrService.arkTextOf(null)).isEmpty();
        assertThat(AsrService.arkTextOf("")).isEmpty();
        assertThat(AsrService.arkTextOf("不是 JSON")).isEmpty();
        assertThat(AsrService.arkTextOf("{}")).isEmpty();
        assertThat(AsrService.arkTextOf("{\"choices\":[]}")).isEmpty();
    }

    @Test
    @DisplayName("模型爱加的外层引号被去掉")
    void stripsWrappingQuotes() {
        assertThat(AsrService.cleanTranscript("\"感冒了怎么办\"")).isEqualTo("感冒了怎么办");
        assertThat(AsrService.cleanTranscript("“感冒了怎么办”")).isEqualTo("感冒了怎么办");
        assertThat(AsrService.cleanTranscript("  感冒了怎么办  ")).isEqualTo("感冒了怎么办");
    }

    @Test
    @DisplayName("模型说没听到的各种说法归一成空串（前端据此提示没听清）")
    void normalizesEmptyMarkers() {
        assertThat(AsrService.cleanTranscript("无")).isEmpty();
        assertThat(AsrService.cleanTranscript("（空）")).isEmpty();
        assertThat(AsrService.cleanTranscript("音频中没有有效人声")).isEmpty();
        assertThat(AsrService.cleanTranscript("")).isEmpty();
        assertThat(AsrService.cleanTranscript(null)).isEmpty();
    }

    @Test
    @DisplayName("正常短句不会被清洗误伤")
    void keepsRealSentences() {
        assertThat(AsrService.cleanTranscript("我今天有点头疼，想吃点药。"))
                .isEqualTo("我今天有点头疼，想吃点药。");
        // 句子中间带"空"字也不能被当成空
        assertThat(AsrService.cleanTranscript("今天空气不错")).isEqualTo("今天空气不错");
    }
}
