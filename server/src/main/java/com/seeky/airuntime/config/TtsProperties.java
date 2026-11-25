package com.seeky.airuntime.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 语音合成（TTS）的音频参数，与 {@link ModelProperties.Endpoint} 分工：
 * 那边管「找谁合成」（地址 / Key / 模型 / 音色），这边管「合成成什么样」与火山侧的协议参数。
 *
 * <p>音色之所以放在 Endpoint 里，是因为它随模型走：2.0 音色（{@code *_uranus_bigtts} /
 * {@code *_moon_bigtts}）必须配 {@code X-Api-Resource-Id: seed-tts-2.0}，配成 1.0 的 resource id
 * 会直接 45000000 音色鉴权失败。
 *
 * @param format          音频容器格式，默认 mp3（流式分片可直接按字节拼接）
 * @param sampleRate      采样率，默认 24000
 * @param maxTextLength   单次合成文本上限。火山没有公开的固定字数口径（超限返回 40402003），
 *                        这里取 600 作为客户端的保守上限；整段接口超了会自动切段，不会失败
 * @param resourceId      火山 {@code X-Api-Resource-Id}：{@code seed-tts-2.0}（2.0 音色）/
 *                        {@code seed-tts-1.0}（1.0 音色）。默认 2.0
 * @param bitRate         MP3 码率，默认 160000（64k~160k 是官方默认区间，低了会有明显毛刺）
 * @param responseTimeout 等响应头/等数据块的上限
 * @param overallTimeout  单次请求从发起到收完的总上限
 */
@ConfigurationProperties(prefix = "seeky.tts")
public record TtsProperties(
        String format,
        Integer sampleRate,
        Integer maxTextLength,
        String resourceId,
        Integer bitRate,
        Duration responseTimeout,
        Duration overallTimeout) {

    private static final String DEFAULT_FORMAT = "mp3";
    private static final int DEFAULT_SAMPLE_RATE = 24000;
    private static final int DEFAULT_MAX_TEXT_LENGTH = 600;
    private static final String DEFAULT_RESOURCE_ID = "seed-tts-2.0";
    private static final int DEFAULT_BIT_RATE = 160_000;

    /** 默认音色：清冷御姐，2.0 音色，气质最贴「嘴硬心软」的人设。 */
    public static final String DEFAULT_VOICE = "zh_female_gaolengyujie_uranus_bigtts";

    public String formatOrDefault() {
        return format == null || format.isBlank() ? DEFAULT_FORMAT : format.strip();
    }

    public int sampleRateOrDefault() {
        return sampleRate == null || sampleRate <= 0 ? DEFAULT_SAMPLE_RATE : sampleRate;
    }

    public int maxTextLengthOrDefault() {
        return maxTextLength == null || maxTextLength <= 0 ? DEFAULT_MAX_TEXT_LENGTH : maxTextLength;
    }

    public String resourceIdOrDefault() {
        return resourceId == null || resourceId.isBlank() ? DEFAULT_RESOURCE_ID : resourceId.strip();
    }

    public int bitRateOrDefault() {
        return bitRate == null || bitRate <= 0 ? DEFAULT_BIT_RATE : bitRate;
    }

    public Duration responseTimeoutOrDefault() {
        return responseTimeout == null || responseTimeout.isNegative() || responseTimeout.isZero()
                ? Duration.ofSeconds(30)
                : responseTimeout;
    }

    public Duration overallTimeoutOrDefault() {
        return overallTimeout == null || overallTimeout.isNegative() || overallTimeout.isZero()
                ? Duration.ofSeconds(90)
                : overallTimeout;
    }

    /** 格式 → MIME，前端 {@code new Blob([...], {type})} 直接用。 */
    public String contentType() {
        return switch (formatOrDefault().toLowerCase()) {
            case "wav" -> "audio/wav";
            case "pcm" -> "audio/pcm";
            case "ogg_opus" -> "audio/ogg";
            default -> "audio/mpeg";
        };
    }
}
