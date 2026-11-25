package com.seeky.airuntime.service.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.seeky.airuntime.config.AuthProperties;
import io.jsonwebtoken.Claims;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JWT 签发与校验。
 *
 * <p>重点不是"能签出来"，而是"假的进不来"：过期、改签名、换密钥、换 issuer
 * 都必须收敛成 {@code Optional.empty()}，调用方只看这一个结果。
 */
class JwtServiceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef";

    private static JwtService service(String secret, long ttl, String issuer) {
        return new JwtService(new AuthProperties(secret, ttl, issuer, 1000));
    }

    private static JwtService service() {
        return service(SECRET, 3600, "ai-soulmate");
    }

    @Test
    @DisplayName("签发 → 校验 往返，sub 是用户 id，claim 带用户名")
    void roundTrip() {
        JwtService jwt = service();

        JwtService.Issued issued = jwt.issue("u_abc123", "chenyu");
        Optional<Claims> claims = jwt.verify(issued.token());

        assertThat(claims).isPresent();
        assertThat(claims.get().getSubject()).isEqualTo("u_abc123");
        assertThat(claims.get().get("username", String.class)).isEqualTo("chenyu");
        assertThat(claims.get().getIssuer()).isEqualTo("ai-soulmate");
        assertThat(issued.expiresIn()).isEqualTo(3600);
    }

    @Test
    @DisplayName("token 里不能出现密码或其他敏感字段（只放 sub + username）")
    void payloadCarriesOnlyIdentity() {
        JwtService jwt = service();

        String token = jwt.issue("u_abc123", "chenyu").token();
        String payload = new String(java.util.Base64.getUrlDecoder()
                .decode(token.split("\\.")[1]), java.nio.charset.StandardCharsets.UTF_8);

        assertThat(payload).contains("u_abc123").contains("chenyu");
        assertThat(payload).doesNotContain("password").doesNotContain("pbkdf2");
    }

    @Test
    @DisplayName("签名被改一个字符立刻失效")
    void rejectsTamperedSignature() {
        JwtService jwt = service();
        String token = jwt.issue("u_1", "alice").token();

        String[] parts = token.split("\\.");
        char last = parts[2].charAt(parts[2].length() - 2);
        parts[2] = parts[2].substring(0, parts[2].length() - 2)
                + (last == 'A' ? 'B' : 'A') + parts[2].charAt(parts[2].length() - 1);

        assertThat(jwt.verify(String.join(".", parts))).isEmpty();
    }

    @Test
    @DisplayName("换了密钥（等同换 JWT_SECRET）旧 token 立刻失效")
    void rejectsTokenFromAnotherSecret() {
        String token = service().issue("u_1", "alice").token();

        JwtService rotated = service("ffffffffffffffffffffffffffffffffffffffffffffffff", 3600, "ai-soulmate");

        assertThat(rotated.verify(token)).isEmpty();
    }

    @Test
    @DisplayName("issuer 不匹配的 token 不认（别人家签的同格式 token 也不能用）")
    void rejectsForeignIssuer() {
        String token = service(SECRET, 3600, "someone-else").issue("u_1", "alice").token();

        assertThat(service().verify(token)).isEmpty();
    }

    @Test
    @DisplayName("签名算法被改成 none 的 token 不认")
    void rejectsAlgNone() {
        JwtService jwt = service();
        String valid = jwt.issue("u_1", "alice").token();
        String header = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"alg\":\"none\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String forged = header + "." + valid.split("\\.")[1] + ".";

        assertThat(jwt.verify(forged)).isEmpty();
    }

    @Test
    @DisplayName("空串 / null / 乱码都返回 empty，不抛异常")
    void rejectsGarbageQuietly() {
        JwtService jwt = service();

        assertThat(jwt.verify(null)).isEmpty();
        assertThat(jwt.verify("")).isEmpty();
        assertThat(jwt.verify("   ")).isEmpty();
        assertThat(jwt.verify("not-a-jwt")).isEmpty();
        assertThat(jwt.verify("a.b.c")).isEmpty();
        assertThat(jwt.verify("...")).isEmpty();
    }

    @Test
    @DisplayName("密钥不足 32 字节也能用（SHA-256 派生），不是直接崩")
    void shortSecretStillWorks() {
        JwtService jwt = service("short", 3600, "ai-soulmate");

        String token = jwt.issue("u_2", "bob").token();

        assertThat(jwt.verify(token)).isPresent();
    }
}
