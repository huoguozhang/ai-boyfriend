package com.seeky.airuntime.dto;

/**
 * 语音识别请求（前端 {@code Composer.jsx} 的契约：录音直接给 Base64）。
 *
 * <p>注意与 ai-runtime 的 {@code SpeechRequest(audioUrl)} 不同：那边是「音频 URL + 异步任务」，
 * 这边浏览器只有一个 Blob。真实 ASR 接入时要么先上传对象存储拿 URL，要么改走实时识别，
 * 所以这个 DTO 暂时只占位、字段按前端现有形态保留。
 *
 * @param audioBase64 录音的 Base64
 * @param mimeType    浏览器给的 MIME，如 audio/webm
 * @param durationMs  录音时长，用于过滤误触
 */
public record SpeechRequest(String audioBase64, String mimeType, Long durationMs) {
}
