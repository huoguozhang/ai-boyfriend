package com.seeky.airuntime.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端随请求带上来的配置（原始 patch，未与 .env 合并）。
 *
 * <p>配置的归属是客户端：设置页改完只写浏览器 localStorage，每次请求放
 * {@code X-Client-Settings} 头里带过来，服务端不落盘、不共享。
 * 因此服务端这里只做「解析」，不做「保存」。
 *
 * <p>头的内容是 Base64URL(JSON)，也可以是裸 JSON（方便 curl 手测）。JSON 结构与设置页一致：
 * <pre>
 * {"services":{"llm":{"protocol":"openai","baseUrl":"...","apiKey":"...","model":"..."}},
 *  "persona":{"name":"小满"},
 *  "prefs":{"receiveMode":"voice"}}
 * </pre>
 * 字段缺省 = 用服务端 .env 的值；{@code apiKey} 空串 = 不覆盖（浏览器里没填 Key 就用后端的）。
 */
public record ClientConfig(Map<String, Map<String, Object>> services,
        Map<String, Object> persona,
        Map<String, Object> prefs) {

    private static final Logger log = LoggerFactory.getLogger(ClientConfig.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final ClientConfig EMPTY =
            new ClientConfig(Map.of(), Map.of(), Map.of());

    /** 请求头名：所有接口通用，前端在 api.js 里统一注入。 */
    public static final String HEADER = "X-Client-Settings";

    public boolean isEmpty() {
        return services.isEmpty() && persona.isEmpty() && prefs.isEmpty();
    }

    /** 解析请求头；空值 / 解析失败都当「没带配置」，回落到 .env，绝不让一个坏头打断对话。 */
    public static ClientConfig parse(String header) {
        if (header == null || header.isBlank()) {
            return EMPTY;
        }
        String raw = header.strip();
        try {
            String json = raw.startsWith("{") ? raw : new String(
                    Base64.getUrlDecoder().decode(pad(raw)), StandardCharsets.UTF_8);
            return fromJson(JSON.readTree(json));
        } catch (Exception ex) {
            // 头太长被截断、前端编码错、手测发错——都只影响这一条请求的配置，不该 500
            log.warn("[settings] 客户端配置解析失败，按 .env 处理：{}", ex.getMessage());
            return EMPTY;
        }
    }

    public static ClientConfig fromJson(JsonNode node) {
        if (node == null || !node.isObject()) {
            return EMPTY;
        }
        return new ClientConfig(
                servicesOf(node.get("services")),
                fieldsOf(node.get("persona")),
                fieldsOf(node.get("prefs")));
    }

    /** 把 {@code {llm:{...}}} 读成 {@code Map<服务名, 字段 patch>}。 */
    private static Map<String, Map<String, Object>> servicesOf(JsonNode node) {
        Map<String, Map<String, Object>> services = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return services;
        }
        node.fieldNames().forEachRemaining(key -> {
            Map<String, Object> fields = fieldsOf(node.get(key));
            if (!fields.isEmpty()) {
                services.put(key, fields);
            }
        });
        return services;
    }

    private static Map<String, Object> fieldsOf(JsonNode node) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return fields;
        }
        node.fieldNames().forEachRemaining(key -> {
            JsonNode value = node.get(key);
            if (value == null || value.isNull()) {
                return;
            }
            fields.put(key, value.isBoolean() ? value.booleanValue()
                    : value.isNumber() ? value.numberValue()
                    : value.asText());
        });
        return fields;
    }

    /** Base64URL 不带 padding，解码器要求补齐。 */
    private static String pad(String value) {
        int mod = value.length() % 4;
        return mod == 0 ? value : value + "=".repeat(4 - mod);
    }
}
