package com.seeky.airuntime.dto;

/**
 * mem0 调用结果：把「真的没有记忆」和「调用失败」分开。
 *
 * <p>这是 ai-runtime 里最容易被忽略、但最要紧的一条：如果解析失败也返回空列表，
 * 调用方会以为「用户没有记忆」，于是重新抽取一遍再写进去，越积越多。
 * 所以这里用 {@code outcome} 显式区分，空列表只在 {@link #NO_HIT} 时代表"确实没有"。
 *
 * @param value   成功时的值
 * @param outcome ok / no-hit / failed / skipped
 * @param reason  降级原因（给人看的，不含密钥）
 */
public record Mem0Result<T>(T value, String outcome, String reason) {

    public static final String OK = "ok";
    public static final String NO_HIT = "no-hit";
    public static final String FAILED = "failed";
    public static final String SKIPPED = "skipped";

    public boolean ok() {
        return OK.equals(outcome);
    }

    public boolean noHit() {
        return NO_HIT.equals(outcome);
    }

    /** 记忆服务没配 / 不可达 / 超时：本轮不带记忆，但对话继续。 */
    public boolean degraded() {
        return SKIPPED.equals(outcome) || FAILED.equals(outcome);
    }

    public static <T> Mem0Result<T> ok(T value) {
        return new Mem0Result<>(value, OK, null);
    }

    public static <T> Mem0Result<T> noHit(T empty) {
        return new Mem0Result<>(empty, NO_HIT, null);
    }

    public static <T> Mem0Result<T> failed(String reason) {
        return new Mem0Result<>(null, FAILED, reason);
    }

    public static <T> Mem0Result<T> skipped() {
        return new Mem0Result<>(null, SKIPPED, "mem0 未启用");
    }
}
