package com.seeky.airuntime.service.auth;

import com.seeky.airuntime.config.AuthProperties;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.stereotype.Component;

/**
 * 密码哈希：PBKDF2-HMAC-SHA256，自带随机盐，比对走常量时间。
 *
 * <p><b>为什么不是 BCrypt</b>：BCrypt 要靠 {@code spring-security-crypto}，而 Boot 4.0.4 管理的是
 * 7.0.4 版、本机 m2 里只有 6.5.x，钉旧版本和 Spring 7 混用会踩坑，为此拉一整个 spring-security 不划算。
 * PBKDF2-HMAC-SHA256 是 JDK 自带（{@code SecretKeyFactory}），210000 轮是 OWASP 当前推荐档位，
 * 安全性上完全够这个场景。
 *
 * <p>存储格式（自描述，换参数不用迁移数据）：
 * <pre>pbkdf2-sha256$210000$&lt;salt-base64&gt;$&lt;hash-base64&gt;</pre>
 */
@Component
public class PasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2-sha256";
    private static final String SEPARATOR = "$";
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private final SecureRandom random = new SecureRandom();
    private final int iterations;

    public PasswordHasher(AuthProperties properties) {
        this.iterations = properties.iterationsOr();
    }

    /** 生成存储串。同一个密码每次结果都不同（盐随机）。 */
    public String hash(String rawPassword) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] derived = derive(rawPassword, salt, iterations);
        Base64.Encoder encoder = Base64.getEncoder();
        return String.join(SEPARATOR, PREFIX, String.valueOf(iterations),
                encoder.encodeToString(salt), encoder.encodeToString(derived));
    }

    /** 校验。格式不对、算法不认得、盐坏了，一律返回 false（不抛异常，调用方不关心原因）。 */
    public boolean matches(String rawPassword, String stored) {
        if (rawPassword == null || stored == null) {
            return false;
        }
        String[] parts = stored.split("\\" + SEPARATOR);
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }
        try {
            int storedIterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = derive(rawPassword, salt, storedIterations);
            // 常量时间比较：别让"比较耗时时长"泄漏哈希前多少个字节对上了
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static byte[] derive(String rawPassword, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(rawPassword.toCharArray(), salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
            throw new IllegalStateException("PBKDF2 不可用：" + ex.getMessage(), ex);
        } finally {
            // 明文口令的 char[] 用完立刻抹掉，别等 GC
            spec.clearPassword();
        }
    }
}
