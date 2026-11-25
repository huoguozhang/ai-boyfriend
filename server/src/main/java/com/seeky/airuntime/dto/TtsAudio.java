package com.seeky.airuntime.dto;

/**
 * 合成好的一整段音频，直接给前端 {@code new Audio()} 播。
 *
 * <p>前端按句切分后一次请求一句（见 {@code front/src/voice.js}），单句通常几十字，
 * 整段返回比拼 SSE 分片简单：拿到就播，不用在浏览器里拼字节、也不用管分片顺序。
 * 需要「边合成边播」时用 {@code /api/v1/speech/tts/stream} 的流式接口。
 *
 * @param audioBase64 完整音频的 Base64（不含 data URI 前缀）
 * @param contentType MIME，如 audio/mpeg
 * @param format      容器格式，如 mp3
 * @param voice       实际使用的音色
 * @param characters  合成文本的字数（码点数，不是字节）
 * @param bytes       音频字节数
 * @param mock        是否为演示音频（未配置真实 TTS 时的提示音，前端可据此不播）
 */
public record TtsAudio(
        String audioBase64,
        String contentType,
        String format,
        String voice,
        int characters,
        int bytes,
        boolean mock) {
}
