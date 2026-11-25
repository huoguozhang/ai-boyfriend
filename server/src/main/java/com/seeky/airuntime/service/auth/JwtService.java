package com.seeky.airuntime.service.auth;

import com.seeky.airuntime.config.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * JWT 签发与校验（HS256，无状态）。
 *
 * <p><b>无状态的含义</b>：服务端不存 session，退出登录就是前端把 token 删掉。所以没有
 * 服务端的"吊销"能力 —— 想立刻踢人得换密钥，代价是所有人一起下线。这个 Demo 不做黑名单。
 *
 * <p>载荷只放 {@code sub}（用户 id）与 {@code username}（省一次查库），
 * <b>不放昵称、头像这些会变的东西</b> —— 它们随时可能改，缓存进 token 就是脏数据。
 *
 * <p>密钥不足 32 字节时先用 SHA-256 压一道：HS256 要求 256 位密钥，直接塞短密钥
 * jjwt 会抛 {@code WeakKeyException}，与其让服务起不来，不如确定性地派生一把。
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    /** HS256 要求的最小密钥长度（字节）。 */
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final long ttlSeconds;
    private final String issuer;

    public JwtService(AuthProperties properties) {
        this.ttlSeconds = properties.ttlSecondsOr();
        this.issuer = properties.issuerOr();
        this.key = Keys.hmacShaKeyFor(resolveSecret(properties.jwtSecret()));
    }

    private static byte[] resolveSecret(String configured) {
        if (configured != null && !configured.isBlank()) {
            byte[] raw = configured.trim().getBytes(StandardCharsets.UTF_8);
            if (raw.length >= MIN_SECRET_BYTES) {
                return raw;
            }
            log.warn("JWT_SECRET 只有 {} 字节（建议 ≥32），已用 SHA-256 派生为 256 位密钥", raw.length);
            return sha256(raw);
        }
        byte[] generated = new byte[48];
        new SecureRandom().nextBytes(generated);
        log.warn("JWT_SECRET 未配置：已生成临时密钥，重启后所有 token 立即失效。"
                + "要稳定就写进 .env：openssl rand -base64 48");
        return generated;
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    /** 签发结果：token + 有效期（秒），前端用后者决定什么时候该重新登录。 */
    public record Issued(String token, long expiresIn) {
    }

    public Issued issue(String userId, String username) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(ttlSeconds);
        String token = Jwts.builder()
                .setId(UUID.randomUUID().toString())
                .setSubject(userId)
                .setIssuer(issuer)
                .claim("username", username)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(expiresAt))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
        return new Issued(token, ttlSeconds);
    }

    /**
     * 校验签名、iss、exp。
     *
     * <p>任何异常都收敛成 {@code Optional.empty()}：过期、签名不对、被篡改、格式垃圾，
     * 对调用方来说都是同一件事 —— 这个 token 不能用。
     */
    public Optional<Claims> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(key)
                    .requireIssuer(issuer)
                    // 容忍 30 秒时钟漂移，避免客户端稍快一点就"刚登录就过期"
                    .setAllowedClockSkewSeconds(30)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            return Optional.of(claims);
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
