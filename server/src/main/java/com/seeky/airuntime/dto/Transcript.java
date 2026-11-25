package com.seeky.airuntime.dto;

/**
 * 一段录音的识别结果。
 *
 * @param text      识别出来的文字（可能是空串：录到了静音 / 只有环境噪声）
 * @param mock      是否是演示结果（没配真实 ASR 时返回固定文本，前端据此不当作真识别）
 * @param latencyMs 从发起到拿到的耗时，前端"测试连接"用
 * @param model     实际使用的模型名，排查用
 */
public record Transcript(String text, boolean mock, long latencyMs, String model) {

    public static Transcript empty(String model, long latencyMs) {
        return new Transcript("", false, latencyMs, model);
    }
}
