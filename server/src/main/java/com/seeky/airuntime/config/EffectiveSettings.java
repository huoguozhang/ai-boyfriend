package com.seeky.airuntime.config;

/**
 * 一次请求的生效配置：{@code .env} 默认值 + 该客户端随请求带上来的覆盖，合并后的不可变快照。
 *
 * <p>为什么要这个类型而不是直接用 {@link RuntimeSettings}：配置现在按**请求**生效，
 * 同一个进程里不同客户端可以有不同的大模型 Key / 人设。共享的可变单例做不到这件事
 * （A 客户端改完，B 客户端的下一句话就换了模型）。所以：
 * <ul>
 *   <li>{@link RuntimeSettings} 只负责「默认值从 .env 来」，进程级、只读；</li>
 *   <li>每个请求在入口解析出 {@code EffectiveSettings}，沿调用链传下去，谁都不许回头读全局。</li>
 * </ul>
 *
 * <p>方法名与 {@link ModelProperties} / {@link CompanionProperties} 一致，
 * 调用点只需把 {@code settings.xxx()} 换成传入的快照即可。
 */
public record EffectiveSettings(ModelProperties models, CompanionProperties companion) {

    public ModelProperties.Endpoint llm() {
        return models.llm();
    }

    public ModelProperties.Endpoint asr() {
        return models.asr();
    }

    public ModelProperties.Endpoint tts() {
        return models.tts();
    }

    public ModelProperties.Endpoint image() {
        return models.image();
    }

    public ModelProperties.Endpoint video() {
        return models.video();
    }

    public CompanionProperties persona() {
        return companion;
    }

    public String name() {
        return companion.name();
    }

    public int age() {
        return companion.age();
    }

    public String city() {
        return companion.city();
    }

    public String job() {
        return companion.job();
    }

    public String personality() {
        return companion.personality();
    }

    public String speakingStyle() {
        return companion.speakingStyle();
    }

    public String backstory() {
        return companion.backstory();
    }

    public String petName() {
        return companion.petName();
    }

    public String voiceHint() {
        return companion.voiceHint();
    }

    public CompanionProperties.Prefs prefs() {
        return companion.prefs();
    }
}
