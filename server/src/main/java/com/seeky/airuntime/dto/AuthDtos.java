package com.seeky.airuntime.dto;

/**
 * 注册 / 登录相关的请求与响应体。
 *
 * <p>{@link AuthUser} 是<b>唯一允许出网的</b>用户结构 —— {@code password_hash} 不进 DTO，
 * 免得哪天顺手 {@code Result.ok(userRow)} 就把哈希带出去了。
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    /** @param nickname 可选，空则用用户名顶上 */
    public record RegisterRequest(String username, String password, String nickname) {
    }

    public record LoginRequest(String username, String password) {
    }

    /**
     * 对外的用户信息。
     *
     * @param createdAt    ISO-8601 字符串（与历史消息的 createdAt 同一套格式）
     * @param lastLoginAt  从没登录过时为 null
     */
    public record AuthUser(String id, String username, String nickname, String createdAt, String lastLoginAt) {
    }

    /**
     * 登录/注册成功的返回。
     *
     * @param token     放进 {@code Authorization: Bearer <token>}
     * @param tokenType 固定 {@code Bearer}
     * @param expiresIn 有效期（秒），前端可据此提前续期或提示重登
     */
    public record AuthResult(String token, String tokenType, long expiresIn, AuthUser user) {
    }
}
