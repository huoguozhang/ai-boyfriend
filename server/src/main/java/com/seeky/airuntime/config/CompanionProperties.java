package com.seeky.airuntime.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 角色卡与聊天偏好，全部可在设置页改（改完写回 {@code .env}，重启仍在）。
 *
 * <p>角色卡是"她是谁"的唯一来源：提示词里只写说话规范，姓名/职业/性格都从这里注入，
 * 所以换人设不需要改任何 prompt 文件。
 */
@ConfigurationProperties(prefix = "seeky.companion")
public record CompanionProperties(
        String name,
        int age,
        String city,
        String job,
        String personality,
        String speakingStyle,
        String backstory,
        String petName,
        String voiceHint,
        Prefs prefs
) {

    public CompanionProperties {
        name = orDefault(name, "小满");
        city = orDefault(city, "杭州");
        job = orDefault(job, "插画师");
        personality = orDefault(personality, "嘴硬心软，嘴上嫌弃但会记住你说过的话");
        speakingStyle = orDefault(speakingStyle, "短句，像微信聊天，偶尔用语气词");
        backstory = orDefault(backstory, "你们认识两年了，住得不远，她有自己的工作和生活");
        petName = orDefault(petName, "布丁");
        voiceHint = orDefault(voiceHint, "年轻女声，语速稍快，带一点懒散");
        if (prefs == null) {
            prefs = new Prefs(null, true, true, 24);
        }
    }

    public static CompanionProperties defaults() {
        return new CompanionProperties(null, 24, null, null, null, null, null, null, null, null);
    }

    /** 聊天偏好（前端设置项）。 */
    public record Prefs(String receiveMode, boolean autoPlay, boolean proactive, int historyLimit) {

        public Prefs {
            if (!"text".equals(receiveMode) && !"voice".equals(receiveMode) && !"both".equals(receiveMode)) {
                receiveMode = "both";
            }
            if (historyLimit < 6 || historyLimit > 60) {
                historyLimit = 24;
            }
        }
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
