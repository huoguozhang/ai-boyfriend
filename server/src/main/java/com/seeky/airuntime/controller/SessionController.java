package com.seeky.airuntime.controller;

import com.seeky.airuntime.auth.AuthContext;
import com.seeky.airuntime.common.BusinessException;
import com.seeky.airuntime.common.ErrorCode;
import com.seeky.airuntime.common.Result;
import com.seeky.airuntime.config.ClientConfig;
import com.seeky.airuntime.config.EffectiveSettings;
import com.seeky.airuntime.config.ModelProperties;
import com.seeky.airuntime.config.RuntimeSettings;
import com.seeky.airuntime.service.auth.AuthService;
import com.seeky.airuntime.service.chat.LlmClient;
import com.seeky.airuntime.service.memory.MemoryService;
import com.seeky.airuntime.service.speech.TtsService;
import com.seeky.airuntime.skill.LocalSkillLoader;
import com.seeky.airuntime.store.ChatStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 首屏与设置：bootstrap、settings 的读取与探活。
 *
 * <p><b>配置归属客户端</b>：设置页改完只写浏览器 localStorage，每次请求放在
 * {@value ClientConfig#HEADER} 头里带过来。服务端不落盘、不共享 —— 所以这里
 * <b>没有任何写入配置的接口</b>，{@code /settings/update} 只是「按你带上来的配置算一份视图返回」。
 *
 * <p><b>客户端优先、可缺失</b>：头里没带的字段回落到 {@code .env}，带了就用客户端的。
 * 但那属于服务端的部分<b>不下发</b> —— {@code services} 只回显该客户端自己填过的字段，
 * 没填的标记 {@code source=server} 且字段留空。前端不需要、也不该知道后端用的是哪家网关、
 * 哪个模型、哪把 Key。{@code capabilities} 只给布尔量（能不能用 / 是不是演示）。
 *
 * <p><b>会话是"每人一个"</b>：bootstrap 返回当前登录用户最近活跃的那个会话；他还没有会话
 * （刚注册）时才新建一条。{@code sessionId} 由服务端给，前端只负责记住并在发消息时带回来。
 */
@RestController
@RequestMapping("/api/v1")
public class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    private static final int HISTORY_LIMIT = 60;

    private final ChatStore store;
    private final RuntimeSettings settings;
    private final MemoryService memory;
    private final LlmClient llm;
    private final TtsService tts;
    private final LocalSkillLoader skills;
    private final AuthService auth;

    public SessionController(ChatStore store, RuntimeSettings settings, MemoryService memory,
            LlmClient llm, TtsService tts, LocalSkillLoader skills, AuthService auth) {
        this.store = store;
        this.settings = settings;
        this.memory = memory;
        this.llm = llm;
        this.tts = tts;
        this.skills = skills;
        this.auth = auth;
    }

    @PostMapping("/session/bootstrap/get")
    public Mono<Result<Map<String, Object>>> bootstrap(
            ServerWebExchange exchange,
            @RequestHeader(value = ClientConfig.HEADER, required = false) String clientHeader) {
        // 身份只认 token：请求体/请求头里没有任何 userId 可以伪造
        String userId = AuthContext.requireUserId(exchange);
        ClientConfig client = ClientConfig.parse(clientHeader);
        EffectiveSettings effective = settings.merge(client);

        String sessionId = store.latestSessionId(userId);
        if (sessionId == null) {
            // 刚注册：给他开一个空会话，前端拿到 id 就能直接发第一句
            sessionId = "s_" + UUID.randomUUID().toString().replace("-", "").substring(0, 18);
            store.ensureSession(sessionId, userId);
        }
        String currentSession = sessionId;

        List<Map<String, Object>> messages = store.history(currentSession, userId, HISTORY_LIMIT);
        return memory.overview(userId, 1000).map(memories -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("sessionId", currentSession);
            body.put("user", auth.me(userId));
            body.put("person", person(effective));
            body.put("prefs", prefs(effective));
            body.put("capabilities", capabilities(effective));
            body.put("messages", messages);
            body.put("memory", Map.of("total", memories.value() == null ? 0 : memories.value().size()));
            body.put("settings", buildSettings(effective, client));
            return Result.ok(body);
        });
    }

    /** 设置页刷新用：角色卡 + 五类服务（只回客户端自己那份）+ 偏好 + 能力。 */
    @PostMapping("/settings/get")
    public Result<Map<String, Object>> getSettings(
            @RequestHeader(value = ClientConfig.HEADER, required = false) String clientHeader) {
        ClientConfig client = ClientConfig.parse(clientHeader);
        return Result.ok(buildSettings(settings.merge(client), client));
    }

    /**
     * 保存配置后的回显：<b>不写任何东西</b>。
     *
     * <p>配置本来就已经在浏览器里了（前端先写 localStorage 再发这个请求），
     * 这里把请求头那份叠上 body 这次改的 patch，算出一份"你现在的实际配置"返回，
     * 前端拿它刷新界面即可。服务端不留痕。
     */
    @PostMapping("/settings/update")
    public Result<Map<String, Object>> update(
            @RequestHeader(value = ClientConfig.HEADER, required = false) String clientHeader,
            @RequestBody(required = false) Map<String, Object> body) {
        ClientConfig client = withPatch(ClientConfig.parse(clientHeader), body);
        EffectiveSettings effective = settings.merge(client);
        // 能力变了，按能力过滤的技能要跟着换（图片/视频/语音技能按需出现）。
        // 这只影响本进程内的技能清单，不属于用户配置，不落盘。
        skills.load();
        return Result.ok(buildSettings(effective, client));
    }

    /**
     * 手工改完 {@code .env} 后重新载入默认值。
     *
     * <p>只影响"没在客户端配置过的那些服务"，客户端自己填的照旧优先。
     */
    @PostMapping("/settings/reload")
    public Result<Map<String, Object>> reload(
            @RequestHeader(value = ClientConfig.HEADER, required = false) String clientHeader) {
        settings.reloadEnv();
        skills.load();
        ClientConfig client = ClientConfig.parse(clientHeader);
        return Result.ok(buildSettings(settings.merge(client), client));
    }

    /**
     * 真发一次最小请求验证配置：大模型出一句话，TTS 合成一小段，其余只校验配置完整性。
     *
     * <p>用的是本次请求的生效配置 —— 客户端填了自己的 Key，测的就是他自己那条路。
     */
    @PostMapping("/settings/test")
    public Mono<Result<Map<String, Object>>> test(
            @RequestHeader(value = ClientConfig.HEADER, required = false) String clientHeader,
            @RequestBody(required = false) Map<String, Object> body) {
        EffectiveSettings effective = settings.merge(ClientConfig.parse(clientHeader));
        String service = body == null ? "llm" : String.valueOf(body.getOrDefault("service", "llm"));
        long started = System.currentTimeMillis();
        return switch (service) {
            case "llm" -> probeLlm(effective, started);
            case "tts" -> probeTts(effective, started);
            case "asr", "image", "video" -> Mono.just(Result.ok(probeStub(effective, service, started)));
            default -> throw new BusinessException(ErrorCode.PARAM_VALIDATION_FAILED, "未知服务：" + service);
        };
    }

    // ------------------------------------------------------------------ 探活

    private Mono<Result<Map<String, Object>>> probeLlm(EffectiveSettings effective, long started) {
        ModelProperties.Endpoint ep = effective.llm();
        if (ep.mock()) {
            return Mono.just(Result.ok(probe("llm", true, 0, "演示协议（mock）：不联网，回复由本地规则模拟", null)));
        }
        if (!ep.configured()) {
            return Mono.just(Result.ok(probe("llm", false, 0, "配置不全，缺少：" + ep.missing(), null)));
        }
        return llm.complete(List.of(Map.of("role", "user", "content", "在吗")), 0, effective)
                .map(reply -> {
                    boolean ok = reply != null && !reply.isBlank();
                    String note = ok
                            ? "连接正常，模型回了 " + reply.strip().length() + " 个字"
                            : "请求发出去了但没拿到内容：baseUrl / Key / 模型名可能不对，看服务端日志 [llm]";
                    return Result.ok(probe("llm", ok, elapsed(started), note, ok ? shorten(reply) : null));
                });
    }

    private Mono<Result<Map<String, Object>>> probeTts(EffectiveSettings effective, long started) {
        ModelProperties.Endpoint ep = effective.tts();
        if (!ep.configured()) {
            return Mono.just(Result.ok(probe("tts", false, 0,
                    ep.enabled() ? "配置不全，缺少：" + ep.missing() : "该能力被禁用（enabled=false）", null)));
        }
        return tts.synthesizeAll("测试一下", null, effective)
                .map(audio -> {
                    String note = audio.mock()
                            ? "演示协议：返回的是提示音，链路已通"
                            : "合成成功：" + audio.bytes() + " 字节 · " + audio.format() + " · " + audio.voice();
                    return Result.ok(probe("tts", true, elapsed(started), note, null));
                })
                .onErrorResume(ex -> {
                    log.warn("[settings] TTS 探活失败: {}", ex.getMessage());
                    return Mono.just(Result.ok(probe("tts", false, elapsed(started),
                            "合成失败：" + ex.getMessage(), null)));
                });
    }

    private Map<String, Object> probeStub(EffectiveSettings effective, String service, long started) {
        ModelProperties.Endpoint ep = switch (service) {
            case "asr" -> effective.asr();
            case "image" -> effective.image();
            default -> effective.video();
        };
        String note = !ep.enabled()
                ? "该能力被禁用（enabled=false）"
                : ep.mock()
                        ? "演示协议（mock）：不联网，出占位内容"
                        : ep.configured()
                                ? "配置齐全；这类能力由对话链路按需调用，这里不单独发请求"
                                : "配置不全，缺少：" + ep.missing();
        return probe(service, ep.configured(), elapsed(started), note, null);
    }

    private Map<String, Object> probe(String service, boolean ok, long latencyMs, String note, String sample) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", service);
        body.put("ok", ok);
        body.put("latencyMs", latencyMs);
        body.put("note", note);
        if (sample != null) {
            body.put("sample", sample);
        }
        return body;
    }

    private static long elapsed(long started) {
        return System.currentTimeMillis() - started;
    }

    private static String shorten(String text) {
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() <= 30 ? flat : flat.substring(0, 30) + "…";
    }

    // ------------------------------------------------------------------ 结构

    private Map<String, Object> buildSettings(EffectiveSettings effective, ClientConfig client) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("persona", person(effective));
        body.put("prefs", prefs(effective));
        body.put("capabilities", capabilities(effective));
        body.put("services", services(client));
        body.put("servicesMeta", servicesMeta());
        body.put("configSource", client.isEmpty() ? "server" : "client");
        return body;
    }

    /** 角色卡：字段名跟前端表单一一对应（tagline / appearance 本项目没有，给空串占位）。 */
    private Map<String, Object> person(EffectiveSettings effective) {
        Map<String, Object> person = new LinkedHashMap<>();
        person.put("name", effective.name());
        person.put("age", effective.age());
        person.put("city", effective.city());
        person.put("job", effective.job());
        person.put("tagline", "");
        person.put("appearance", "");
        person.put("voiceHint", effective.voiceHint());
        person.put("personality", effective.personality());
        person.put("speakingStyle", effective.speakingStyle());
        person.put("backstory", effective.backstory());
        person.put("petName", effective.petName());
        return person;
    }

    private Map<String, Object> prefs(EffectiveSettings effective) {
        Map<String, Object> prefs = new LinkedHashMap<>();
        prefs.put("receiveMode", effective.prefs().receiveMode());
        prefs.put("autoPlay", effective.prefs().autoPlay());
        prefs.put("proactive", effective.prefs().proactive());
        return prefs;
    }

    /** 能力只给布尔量：configured / mock / missing（缺哪些字段名，不含值）。 */
    private Map<String, Object> capabilities(EffectiveSettings effective) {
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("llm", describe(effective.llm()));
        caps.put("asr", describe(effective.asr()));
        caps.put("tts", describe(effective.tts()));
        caps.put("image", describe(effective.image()));
        caps.put("video", describe(effective.video()));
        return caps;
    }

    private Map<String, Object> describe(ModelProperties.Endpoint endpoint) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("configured", endpoint.configured());
        item.put("mock", endpoint.mock());
        item.put("missing", endpoint.missing());
        return item;
    }

    /**
     * 五类服务：<b>只回客户端自己填过的东西</b>。
     *
     * <p>没填的服务一律 {@code source=server}、字段空 —— 后端的 baseUrl / 模型 / Key
     * 一个字节都不下发，前端显示的"已配置"只来自它自己 localStorage 里那把 Key 的掩码。
     */
    private Map<String, Object> services(ClientConfig client) {
        Map<String, Object> services = new LinkedHashMap<>();
        services.put("llm", service(client, "llm"));
        services.put("asr", service(client, "asr"));
        services.put("tts", service(client, "tts"));
        services.put("image", service(client, "image"));
        services.put("video", service(client, "video"));
        return services;
    }

    private Map<String, Object> service(ClientConfig client, String key) {
        Map<String, Object> mine = client.services().getOrDefault(key, Map.of());
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("source", mine.isEmpty() ? "server" : "client");
        item.put("protocol", mine.getOrDefault("protocol", ""));
        item.put("baseUrl", mine.getOrDefault("baseUrl", ""));
        item.put("model", mine.getOrDefault("model", ""));
        item.put("voice", mine.getOrDefault("voice", ""));
        item.put("hasKey", !String.valueOf(mine.getOrDefault("apiKey", "")).isBlank());
        item.put("enabled", !"false".equalsIgnoreCase(String.valueOf(mine.getOrDefault("enabled", "true"))));
        return item;
    }

    /**
     * 设置页卡片的元信息（顺序即展示顺序）。
     *
     * <p>协议候选与字段清单都由服务端给：前端只负责渲染，加一类能力不用动前端。
     */
    private List<Map<String, Object>> servicesMeta() {
        List<Map<String, Object>> meta = new ArrayList<>();
        meta.add(metaItem("llm", "大模型", "她说话靠它", true,
                List.of(protocol("openai", "OpenAI 兼容"), protocol("volcengine", "火山 volcengine"),
                        protocol("mock", "演示")),
                List.of("baseUrl", "apiKey", "model"),
                "填 OpenAI 兼容端点的 /v1 根路径、Key 与模型名；留空就用服务端默认的那份。"));
        meta.add(metaItem("asr", "语音识别", "把你说的转成文字", false,
                List.of(protocol("volcengine", "火山 volcengine"), protocol("mock", "演示")),
                List.of("baseUrl", "apiKey", "model"),
                "走豆包录音文件识别·极速版；留空就用服务端默认的那份。"));
        meta.add(metaItem("tts", "语音合成", "把她的回复读出来", false,
                List.of(protocol("volcengine", "火山 volcengine"), protocol("openai", "OpenAI 兼容"),
                        protocol("mock", "演示")),
                List.of("baseUrl", "apiKey", "model", "voice"),
                "音色与 resource id 必须配套：*_uranus_bigtts 配 seed-tts-2.0。"));
        meta.add(metaItem("image", "图像生成", "她给你发的照片", false,
                List.of(protocol("openai", "OpenAI 兼容"), protocol("mock", "演示")),
                List.of("baseUrl", "apiKey", "model"),
                "配 mock 时出本地占位图。"));
        meta.add(metaItem("video", "视频生成", "她给你发的小视频", false,
                List.of(protocol("openai", "OpenAI 兼容"), protocol("mock", "演示")),
                List.of("baseUrl", "apiKey", "model"),
                "生成是异步任务，前端会轮询结果。"));
        return meta;
    }

    private Map<String, Object> metaItem(String key, String name, String desc, boolean required,
            List<Map<String, Object>> protocols, List<String> fields, String fieldHint) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", key);
        item.put("label", name);
        item.put("name", name);
        item.put("desc", desc);
        item.put("hint", fieldHint);
        item.put("required", required);
        item.put("protocols", protocols);
        item.put("fields", fields);
        return item;
    }

    private static Map<String, Object> protocol(String value, String label) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("value", value);
        item.put("label", label);
        return item;
    }

    // ------------------------------------------------------------------ 入参

    /** 把 body 这次的 patch 叠到请求头那份客户端配置上（纯内存，不落盘）。 */
    private static ClientConfig withPatch(ClientConfig base, Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return base;
        }
        Map<String, Map<String, Object>> services = new LinkedHashMap<>(base.services());
        if (body.get("services") instanceof Map<?, ?> incoming) {
            incoming.forEach((key, value) -> {
                if (key == null || !(value instanceof Map<?, ?> fields)) {
                    return;
                }
                Map<String, Object> merged = new LinkedHashMap<>(
                        services.getOrDefault(String.valueOf(key), Map.of()));
                asMap(fields).forEach((field, fieldValue) -> {
                    // 空 Key 表示「不覆盖」，不能把客户端已有的 Key 抹掉
                    if ("apiKey".equals(field) && String.valueOf(fieldValue).isBlank()) {
                        return;
                    }
                    merged.put(field, fieldValue);
                });
                services.put(String.valueOf(key), merged);
            });
        }
        Map<String, Object> persona = new LinkedHashMap<>(base.persona());
        persona.putAll(asMap(body.get("persona")));
        Map<String, Object> prefs = new LinkedHashMap<>(base.prefs());
        prefs.putAll(asMap(body.get("prefs")));
        return new ClientConfig(services, persona, prefs);
    }

    private static Map<String, Object> asMap(Object raw) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> source) {
            source.forEach((key, value) -> {
                if (key != null) {
                    map.put(String.valueOf(key), value);
                }
            });
        }
        return map;
    }
}
