package com.seeky.airuntime.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 五类 AI 服务的配置（大模型 / 语音识别 / 语音合成 / 图像 / 视频），全部来自 {@code .env}。
 *
 * <p>沿用 ai-runtime「一类能力一份配置」的形态，但把数据源从 Nacos 换成环境变量：
 * {@code application.yml} 里写成 {@code ${LLM_BASE_URL:}} 这样的占位，{@code .env} 由
 * {@code spring.config.import} 作为 properties 导入，因此改 .env 重启即生效，代码与仓库里没有任何 Key。
 *
 * <p>{@code protocol=mock} 是内置演示协议：不联网、不要 Key，也能走完对话/语音/发图的完整链路，
 * 用于无 Key 验收。某类能力没配好时只有它自己不可用，其余照常（降级在调用方判断 {@link Endpoint#configured()}）。
 */
@ConfigurationProperties(prefix = "seeky.model")
public record ModelProperties(
        Endpoint llm,
        Endpoint asr,
        Endpoint tts,
        Endpoint image,
        Endpoint video
) {

    /**
     * 一类服务的配置。
     *
     * @param enabled  是否启用（false = 该能力直接不可用，比"配错了"更明确）
     * @param protocol 协议：openai（OpenAI 兼容）/ volcengine（火山原生，TTS 用）/ mock
     * @param baseUrl  接口地址（OpenAI 兼容服务的 /v1 根路径）
     * @param apiKey   Key，只存在服务端，接口只回掩码
     * @param model    模型名
     * @param voice    音色（仅 TTS 用）
     */
    public record Endpoint(
            boolean enabled,
            String protocol,
            String baseUrl,
            String apiKey,
            String model,
            String voice
    ) {

        public Endpoint {
            protocol = blankTo(protocol, "openai");
            baseUrl = trimToEmpty(baseUrl);
            apiKey = trimToEmpty(apiKey);
            model = trimToEmpty(model);
            voice = trimToEmpty(voice);
        }

        /** 演示模式：不联网，走本地规则。 */
        public boolean mock() {
            return "mock".equalsIgnoreCase(protocol);
        }

        /** 这一项能不能用：mock 只要 enabled；真实协议要求地址/Key/模型都齐。 */
        public boolean configured() {
            if (!enabled) {
                return false;
            }
            if (mock()) {
                return true;
            }
            return !baseUrl.isBlank() && !apiKey.isBlank() && !model.isBlank();
        }

        /** 缺了哪几项，用于前端提示与启动日志。 */
        public String missing() {
            if (mock() || !enabled) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            if (baseUrl.isBlank()) {
                sb.append("baseUrl ");
            }
            if (apiKey.isBlank()) {
                sb.append("apiKey ");
            }
            if (model.isBlank()) {
                sb.append("model ");
            }
            return sb.toString().trim();
        }

        /** Key 脱敏视图：永不把明文回给前端。 */
        public String maskedKey() {
            if (apiKey.isBlank()) {
                return "";
            }
            if (apiKey.length() <= 8) {
                return "••••";
            }
            return apiKey.substring(0, 4) + "••••" + apiKey.substring(apiKey.length() - 4);
        }

        private static String blankTo(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }

        private static String trimToEmpty(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
