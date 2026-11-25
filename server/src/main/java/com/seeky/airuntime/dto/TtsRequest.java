package com.seeky.airuntime.dto;

/**
 * 语音合成请求。
 *
 * @param text  待合成文本（前端已按句切分，一句一次）
 * @param voice 音色，空则用 {@code .env} 里的 TTS_VOICE
 */
public record TtsRequest(String text, String voice) {
}
