package com.seeky.airuntime.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本地 mem0 服务配置（{@code seeky.mem0}）。
 *
 * <p>与 ai-runtime 的 {@code Mem0Properties} 同名同义，只改了两处：
 * <ul>
 *   <li>{@code serviceName}（Nacos 服务发现）换成 {@code baseUrl} —— 本项目不用 Nacos，
 *       直接指向本机 docker 里的 mem0（默认 {@code http://127.0.0.1:6101}）；</li>
 *   <li>Key 从 Nacos 配置中心改成 {@code .env} 的 {@code MEM0_API_KEY}，仍然只以
 *       {@code X-API-Key} 头发出去，不落日志。</li>
 * </ul>
 *
 * <p>身份维度保持一致：{@code user_id} 隔离用户，{@code run_id} 标会话（或 {@code persona}），
 * {@code agent_id} 固定。
 *
 * @param enabled        是否启用记忆（false = 读返回空、写跳过，对话不受影响）
 * @param baseUrl        mem0 服务地址
 * @param apiKey         X-API-Key；留空表示服务端未开校验
 * @param agentId        mem0 agent_id 维度
 * @param searchLimit    单次检索条数
 * @param maxContextChars 注入 system prompt 的记忆文本总字符上限
 * @param responseTimeout 单次调用超时；超时按降级处理，不阻塞回复
 */
@ConfigurationProperties(prefix = "seeky.mem0")
public record Mem0Properties(
        boolean enabled,
        String baseUrl,
        String apiKey,
        String agentId,
        Integer searchLimit,
        Integer maxContextChars,
        Duration responseTimeout
) {

    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:6101";
    private static final String DEFAULT_AGENT_ID = "xiaoman";
    private static final int DEFAULT_SEARCH_LIMIT = 8;
    private static final int DEFAULT_MAX_CONTEXT_CHARS = 4096;

    public Mem0Properties {
        baseUrl = baseUrl == null || baseUrl.isBlank()
                ? DEFAULT_BASE_URL : baseUrl.trim().replaceAll("/+$", "");
        apiKey = apiKey == null ? "" : apiKey.trim();
        agentId = agentId == null || agentId.isBlank() ? DEFAULT_AGENT_ID : agentId.trim();
        if (responseTimeout == null) {
            responseTimeout = Duration.ofSeconds(5);
        }
    }

    /** 能不能真调：关掉开关或没地址都算不可用，调用方走降级。 */
    public boolean configured() {
        return enabled && !baseUrl.isBlank();
    }

    public int searchLimitOrDefault() {
        return searchLimit == null || searchLimit < 1 ? DEFAULT_SEARCH_LIMIT : searchLimit;
    }

    public int maxContextCharsOrDefault() {
        return maxContextChars == null || maxContextChars < 1
                ? DEFAULT_MAX_CONTEXT_CHARS : maxContextChars;
    }
}
