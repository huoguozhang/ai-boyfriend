package com.seeky.airuntime.dto;

import java.util.Map;

/**
 * 一轮对话的请求体（字段以前端 {@code useChat.js} 发出的为准）。
 *
 * @param sessionId 会话 id，前端首次打开生成并持久化
 * @param text      文本内容（语音输入时是识别后的文字）
 * @param inputMode text / voice
 * @param audio     语音附加信息（时长等），目前只用于前端展示
 * @param userId    可选；单机 Demo 不传就用 local-user
 */
public record ChatRequest(
        String sessionId,
        String text,
        String inputMode,
        Map<String, Object> audio,
        String userId
) {

    public String sessionIdOr(String fallback) {
        return sessionId == null || sessionId.isBlank() ? fallback : sessionId.trim();
    }

    public String userIdOr(String fallback) {
        return userId == null || userId.isBlank() ? fallback : userId.trim();
    }
}
