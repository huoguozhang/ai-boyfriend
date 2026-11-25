package com.seeky.airuntime.config;

import com.seeky.airuntime.common.BusinessException;
import com.seeky.airuntime.common.ErrorCode;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 默认配置：{@code .env} 绑定出来的那一份，只读、进程级。
 *
 * <p><b>这里不存任何"用户配置"</b>。设置页改的东西属于客户端（浏览器 localStorage），
 * 每次请求通过 {@code X-Client-Settings} 头带来，由 {@link #merge(ClientConfig)} 叠加到默认值上，
 * 产出一个只对这次请求生效的 {@link EffectiveSettings}。
 *
 * <p>为什么这样设计（而不是像以前那样写 {@code data/settings.properties}）：
 * 写服务端文件等于「A 客户端一改，所有人的模型和 Key 都跟着变」，
 * 而设置页本来就是一个人的偏好——它不该有全局副作用。
 *
 * <p>{@link #reloadEnv()} 仍然保留：手工编辑 {@code .env} 后想立即生效，不用重启进程
 * （嵌入模型这类进程级配置仍需重启）。
 */
@Component
public class RuntimeSettings {

    private static final Logger log = LoggerFactory.getLogger(RuntimeSettings.class);

    private static final Path ENV_FILE = Paths.get(".env");

    private static final Set<String> SERVICE_KEYS = Set.of("llm", "asr", "tts", "image", "video");

    /** .env 绑定出来的默认值（reloadEnv 会整体替换）——不可变，代表"出厂设置"。 */
    private volatile ModelProperties envModels;
    private volatile CompanionProperties envCompanion;

    public RuntimeSettings(ModelProperties models, CompanionProperties companion) {
        this.envModels = models;
        this.envCompanion = companion;
        log.info("[settings] 默认配置来自 .env（模型 {}）；设置页的修改只存在浏览器，随请求带上来",
                models.llm().model());
    }

    /** 默认配置（没有任何客户端覆盖时用它）。 */
    public EffectiveSettings defaults() {
        return new EffectiveSettings(envModels, envCompanion);
    }

    /**
     * 把客户端带来的 patch 叠到默认值上，得到本次请求的生效配置。
     *
     * <p>合并语义与设置页一致：
     * <ul>
     *   <li>没提交的字段沿用默认值；</li>
     *   <li>{@code apiKey} 空串 = 不覆盖（浏览器里没填 Key 就走 .env 那把）；</li>
     *   <li>其余字段按提交值覆盖，空串就是清空。</li>
     * </ul>
     */
    public EffectiveSettings merge(ClientConfig client) {
        EffectiveSettings base = defaults();
        if (client == null || client.isEmpty()) {
            return base;
        }
        ModelProperties models = new ModelProperties(
                patch(client, "llm", base.llm()),
                patch(client, "asr", base.asr()),
                patch(client, "tts", base.tts()),
                patch(client, "image", base.image()),
                patch(client, "video", base.video()));
        return new EffectiveSettings(models, patchCompanion(base.persona(), client));
    }

    /** 重新读 {@code .env} 作为默认值；客户端覆盖不受影响（它们本来就不在这里）。 */
    public synchronized void reloadEnv() {
        if (!Files.isReadable(ENV_FILE)) {
            log.warn("[settings] 找不到 .env，保持当前默认值：{}", ENV_FILE.toAbsolutePath());
            return;
        }
        Properties env = new Properties();
        try (Reader reader = Files.newBufferedReader(ENV_FILE, StandardCharsets.UTF_8)) {
            env.load(reader);
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "读取 .env 失败：" + ex.getMessage());
        }
        envModels = fromEnv(env);
        envCompanion = companionFromEnv(env);
        log.info("[settings] 已重新载入 .env（模型 {}）", envModels.llm().model());
    }

    // ------------------------------------------------------------------ 内部：合并

    private ModelProperties.Endpoint patch(ClientConfig client, String key, ModelProperties.Endpoint fallback) {
        Map<String, Object> fields = client.services().get(key);
        return fields == null || fields.isEmpty() ? fallback : merge(fallback, fields);
    }

    private ModelProperties.Endpoint merge(ModelProperties.Endpoint current, Map<String, Object> patch) {
        String protocol = str(patch, "protocol", current.protocol());
        // 空串 = 这个字段没填，继续用默认协议（前端表单清空协议时不该报错）
        if (protocol.isBlank()) {
            protocol = current.protocol();
        }
        if (!"openai".equalsIgnoreCase(protocol) && !"volcengine".equalsIgnoreCase(protocol)
                && !"mock".equalsIgnoreCase(protocol)) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_FAILED, "不支持的协议：" + protocol);
        }
        String apiKey = current.apiKey();
        if (patch.containsKey("apiKey")) {
            String raw = raw(patch.get("apiKey")).trim();
            // 留空 = 不覆盖：浏览器里没填 Key，就用 .env 里那把
            if (!raw.isEmpty()) {
                apiKey = raw;
            }
        }
        return new ModelProperties.Endpoint(
                patch.containsKey("enabled") ? bool(patch, "enabled", current.enabled()) : current.enabled(),
                protocol,
                str(patch, "baseUrl", current.baseUrl()),
                apiKey,
                str(patch, "model", current.model()),
                str(patch, "voice", current.voice()));
    }

    private CompanionProperties patchCompanion(CompanionProperties base, ClientConfig client) {
        Map<String, Object> p = client.persona();
        Map<String, Object> prefs = client.prefs();
        if (p.isEmpty() && prefs.isEmpty()) {
            return base;
        }
        CompanionProperties.Prefs cur = base.prefs();
        CompanionProperties.Prefs nextPrefs = prefs.isEmpty() ? cur : new CompanionProperties.Prefs(
                str(prefs, "receiveMode", cur.receiveMode()),
                bool(prefs, "autoPlay", cur.autoPlay()),
                bool(prefs, "proactive", cur.proactive()),
                integer(prefs, "historyLimit", cur.historyLimit()));
        return new CompanionProperties(
                str(p, "name", base.name()),
                integer(p, "age", base.age()),
                str(p, "city", base.city()),
                str(p, "job", base.job()),
                str(p, "personality", base.personality()),
                str(p, "speakingStyle", base.speakingStyle()),
                str(p, "backstory", base.backstory()),
                str(p, "petName", base.petName()),
                str(p, "voiceHint", base.voiceHint()),
                nextPrefs);
    }

    // ------------------------------------------------------------------ 内部：.env → 默认值

    /** 与 {@code application.yml} 的占位符一一对应，改这里记得同步 yml。 */
    private static ModelProperties fromEnv(Properties env) {
        return new ModelProperties(
                endpoint(env, "LLM", ""),
                endpoint(env, "ASR", ""),
                endpoint(env, "TTS", ""),
                endpoint(env, "IMAGE", ""),
                endpoint(env, "VIDEO", ""));
    }

    private static ModelProperties.Endpoint endpoint(Properties env, String prefix, String voiceDefault) {
        return new ModelProperties.Endpoint(
                bool(env, prefix + "_ENABLED", true),
                get(env, prefix + "_PROTOCOL", "mock"),
                get(env, prefix + "_BASE_URL", ""),
                get(env, prefix + "_API_KEY", ""),
                get(env, prefix + "_MODEL", ""),
                get(env, prefix + "_VOICE", voiceDefault));
    }

    private static CompanionProperties companionFromEnv(Properties env) {
        return new CompanionProperties(
                get(env, "PERSONA_NAME", "小满"),
                parseInt(env.getProperty("PERSONA_AGE"), 24),
                get(env, "PERSONA_CITY", "杭州"),
                get(env, "PERSONA_JOB", "插画师"),
                get(env, "PERSONA_PERSONALITY", "嘴硬心软，嘴上嫌弃但会记住你说过的话"),
                get(env, "PERSONA_SPEAKING_STYLE", "短句，像微信聊天，偶尔用语气词"),
                get(env, "PERSONA_BACKSTORY", "你们认识两年了，住得不远，她有自己的工作和生活"),
                get(env, "PERSONA_PET_NAME", "布丁"),
                get(env, "PERSONA_VOICE_HINT", "年轻女声，语速稍快，带一点懒散"),
                new CompanionProperties.Prefs(
                        get(env, "CHAT_RECEIVE_MODE", "both"),
                        bool(env, "CHAT_AUTO_PLAY", true),
                        bool(env, "CHAT_PROACTIVE", true),
                        parseInt(env.getProperty("CHAT_HISTORY_LIMIT"), 24)));
    }

    // ------------------------------------------------------------------ 小工具

    private static String get(Properties p, String key, String fallback) {
        String value = p.getProperty(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static boolean bool(Properties p, String key, boolean fallback) {
        String value = p.getProperty(key);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value.trim());
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String raw(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String str(Map<String, Object> patch, String key, String fallback) {
        if (!patch.containsKey(key)) {
            return fallback;
        }
        return raw(patch.get(key)).trim();
    }

    private static boolean bool(Map<String, Object> patch, String key, boolean fallback) {
        if (!patch.containsKey(key)) {
            return fallback;
        }
        Object value = patch.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        String text = raw(value).trim();
        return text.isEmpty() ? fallback : Boolean.parseBoolean(text);
    }

    private static int integer(Map<String, Object> patch, String key, int fallback) {
        if (!patch.containsKey(key)) {
            return fallback;
        }
        Object value = patch.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        return parseInt(raw(value), fallback);
    }

    /** 调试用：默认值快照（不含 Key）。 */
    public Map<String, Object> snapshot() {
        Map<String, Object> body = new LinkedHashMap<>();
        EffectiveSettings s = defaults();
        body.put("llm", describe(s.llm()));
        body.put("asr", describe(s.asr()));
        body.put("tts", describe(s.tts()));
        body.put("image", describe(s.image()));
        body.put("video", describe(s.video()));
        return body;
    }

    private Map<String, Object> describe(ModelProperties.Endpoint ep) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("protocol", ep.protocol());
        item.put("baseUrl", ep.baseUrl());
        item.put("model", ep.model());
        item.put("voice", ep.voice());
        item.put("maskedKey", ep.maskedKey());
        item.put("configured", ep.configured());
        return item;
    }
}
