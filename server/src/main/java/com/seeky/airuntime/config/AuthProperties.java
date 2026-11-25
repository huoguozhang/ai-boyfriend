package com.seeky.airuntime.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 鉴权相关配置（{@code seeky.auth.*}），值来自 {@code server/.env}。
 *
 * @param jwtSecret        HS256 签名密钥。留空则启动时随机生成一把 —— 能跑，但重启后
 *                         所有人都会被踢回登录页（内存里的密钥变了），所以只当兜底。
 *                         想稳定就配一个 ≥32 字节的随机串：{@code openssl rand -base64 48}
 * @param jwtTtlSeconds    token 有效期，默认 7 天。陪伴类应用不需要用户天天重登
 * @param jwtIssuer        iss 声明，校验时用它绑定"这确实是我签的"
 * @param pbkdf2Iterations 密码哈希迭代次数，默认 210000（OWASP 对 PBKDF2-HMAC-SHA256 的推荐档位）
 */
@ConfigurationProperties(prefix = "seeky.auth")
public record AuthProperties(
        String jwtSecret,
        long jwtTtlSeconds,
        String jwtIssuer,
        int pbkdf2Iterations) {

    private static final long DEFAULT_TTL_SECONDS = 7L * 24 * 60 * 60;
    private static final String DEFAULT_ISSUER = "ai-soulmate";
    private static final int DEFAULT_PBKDF2_ITERATIONS = 210_000;

    public long ttlSecondsOr() {
        return jwtTtlSeconds > 0 ? jwtTtlSeconds : DEFAULT_TTL_SECONDS;
    }

    public String issuerOr() {
        return jwtIssuer == null || jwtIssuer.isBlank() ? DEFAULT_ISSUER : jwtIssuer.trim();
    }

    public int iterationsOr() {
        return pbkdf2Iterations > 0 ? pbkdf2Iterations : DEFAULT_PBKDF2_ITERATIONS;
    }
}
