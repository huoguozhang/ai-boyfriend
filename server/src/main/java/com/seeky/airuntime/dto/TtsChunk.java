package com.seeky.airuntime.dto;

/**
 * 一段合成出来的音频（流式分片）。
 *
 * <p>火山流式协议里，一包要么带 {@code data}（Base64 音频片段），要么是中间的时间戳包
 * （{@code data=null, sentence:{...}}），最后一包 {@code code=20000000} 是结束。
 * 所以用 {@link #terminal()} 区分终止，而不是靠「audio 为空」判断 —— 时间戳包的 audio 也是空的。
 *
 * @param audioBase64  Base64 音频片段；终帧为空串
 * @param terminal     是否终帧
 * @param finishReason 终帧原因（目前只有 stop）
 */
public record TtsChunk(String audioBase64, boolean terminal, String finishReason) {

    public boolean hasAudio() {
        return audioBase64 != null && !audioBase64.isBlank();
    }

    public static TtsChunk audio(String base64) {
        return new TtsChunk(base64, false, null);
    }

    public static TtsChunk end() {
        return new TtsChunk("", true, "stop");
    }
}
