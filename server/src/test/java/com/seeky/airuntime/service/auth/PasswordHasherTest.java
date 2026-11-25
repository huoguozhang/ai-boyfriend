package com.seeky.airuntime.service.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.seeky.airuntime.config.AuthProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 密码哈希的行为约定。
 *
 * <p>迭代次数压到 1000 只为跑得快 —— 生产是 21 万轮，逻辑完全一样，快的只是 KDF 循环。
 * 这里要守住的是"能不能认对/认错"，不是"跑多久"。
 */
class PasswordHasherTest {

    private static PasswordHasher hasher() {
        return new PasswordHasher(new AuthProperties("test-secret", 3600, "ai-soulmate", 1000));
    }

    @Test
    @DisplayName("哈希串是自描述格式，换参数不用迁数据")
    void hashIsSelfDescribing() {
        String stored = hasher().hash("correct horse");

        assertThat(stored.split("\\$")).hasSize(4);
        assertThat(stored).startsWith("pbkdf2-sha256$1000$");
    }

    @Test
    @DisplayName("同一个密码两次哈希必须不同（盐是随机的）")
    void saltIsRandom() {
        PasswordHasher hasher = hasher();

        String first = hasher.hash("same-password");
        String second = hasher.hash("same-password");

        assertThat(first).isNotEqualTo(second);
        // 但两个都能验过 —— 盐变了，哈希也就变了，验证靠串里自带的那个盐
        assertThat(hasher.matches("same-password", first)).isTrue();
        assertThat(hasher.matches("same-password", second)).isTrue();
    }

    @Test
    @DisplayName("正确密码通过")
    void matchesCorrectPassword() {
        PasswordHasher hasher = hasher();
        String stored = hasher.hash("s3cret!");

        assertThat(hasher.matches("s3cret!", stored)).isTrue();
    }

    @Test
    @DisplayName("错误密码、大小写变化、多一个空格都不通过")
    void rejectsWrongPassword() {
        PasswordHasher hasher = hasher();
        String stored = hasher.hash("s3cret!");

        assertThat(hasher.matches("s3cret", stored)).isFalse();
        assertThat(hasher.matches("S3cret!", stored)).isFalse();
        assertThat(hasher.matches("s3cret! ", stored)).isFalse();
        assertThat(hasher.matches("", stored)).isFalse();
    }

    @Test
    @DisplayName("哈希尾部被改一个字符就验不过（常量时间比较真的在比）")
    void detectsTamperedHash() {
        PasswordHasher hasher = hasher();
        String stored = hasher.hash("s3cret!");
        String[] parts = stored.split("\\$");

        char last = parts[3].charAt(parts[3].length() - 1);
        parts[3] = parts[3].substring(0, parts[3].length() - 1) + (last == 'A' ? 'B' : 'A');
        String tampered = String.join("$", parts);

        assertThat(hasher.matches("s3cret!", tampered)).isFalse();
    }

    @Test
    @DisplayName("格式垃圾 / null 一律 false，不抛异常（登录入口不能被一个坏串打成 500）")
    void rejectsGarbageInsteadOfThrowing() {
        PasswordHasher hasher = hasher();

        assertThat(hasher.matches("x", null)).isFalse();
        assertThat(hasher.matches(null, "pbkdf2-sha256$1$a$b")).isFalse();
        assertThat(hasher.matches("x", "")).isFalse();
        assertThat(hasher.matches("x", "bcrypt$whatever")).isFalse();
        assertThat(hasher.matches("x", "pbkdf2-sha256$not-a-number$aa$bb")).isFalse();
        assertThat(hasher.matches("x", "pbkdf2-sha256$1000$不是base64$也不是")).isFalse();
    }

    @Test
    @DisplayName("UTF-8 密码不会被截断（中文口令也是口令）")
    void handlesUnicodePassword() {
        PasswordHasher hasher = hasher();
        String password = "小满今天也不开心";
        String stored = hasher.hash(password);

        assertThat(hasher.matches(password, stored)).isTrue();
        assertThat(hasher.matches("小满今天很开心", stored)).isFalse();
    }
}
