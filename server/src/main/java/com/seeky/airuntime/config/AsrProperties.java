package com.seeky.airuntime.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 语音识别（ASR）的协议参数，与 {@link ModelProperties.Endpoint} 分工：
 * 那边管「找谁识别」（地址 / Key / 模型），这边管「火山侧的协议参数」。
 *
 * <p>为什么单独要一个 {@code resource-id}：豆包语音的鉴权是「API Key + 资源 ID」两件套，
 * 资源 ID 决定走哪个模型档位（极速版 {@code volc.bigasr.auc_turbo} / 标准版 {@code volc.bigasr.auc}）。
 * 填错的表现是 45000000 鉴权失败，不是 Key 错。
 *
 * @param resourceId  火山 {@code X-Api-Resource-Id}，默认极速版（同步返回，适合一段录音立刻要文字）
 * @param userId      {@code X-Api-*}/{@code user.uid} 里的用户标识，仅用于火山侧统计
 * @param timeout     单次识别的总超时
 */
@ConfigurationProperties(prefix = "seeky.asr")
public record AsrProperties(String resourceId, String userId, Duration timeout) {

    private static final String DEFAULT_RESOURCE_ID = "volc.bigasr.auc_turbo";
    private static final String DEFAULT_USER_ID = "soulmate";

    public String resourceIdOrDefault() {
        return resourceId == null || resourceId.isBlank() ? DEFAULT_RESOURCE_ID : resourceId.strip();
    }

    public String userIdOrDefault() {
        return userId == null || userId.isBlank() ? DEFAULT_USER_ID : userId.strip();
    }

    public Duration timeoutOrDefault() {
        return timeout == null || timeout.isNegative() || timeout.isZero()
                ? Duration.ofSeconds(30)
                : timeout;
    }
}
