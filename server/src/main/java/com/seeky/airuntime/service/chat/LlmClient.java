package com.seeky.airuntime.service.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seeky.airuntime.config.ModelProperties;
import com.seeky.airuntime.config.EffectiveSettings;
import com.seeky.airuntime.config.RuntimeSettings;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.util.retry.Retry;

/**
 * 大模型客户端（OpenAI 兼容协议，当前接火山方舟 Agent Plan）。
 *
 * <p>为什么不像 ai-runtime 那样用 Spring AI 的 ChatModel：那边接的是 DashScope 官方 SDK，
 * 这里是一个 OpenAI 兼容端点，用 WebClient 直连少一层适配；协议就是
 * {@code POST /chat/completions}，流式与阻塞两种形态各一个方法。
 *
 * <p>火山这个模型会在 {@code delta.reasoning_content} 里回思考过程，**只取 {@code delta.content}** ——
 * 把思考过程吐给用户，她就会开始自言自语"用户在问我在干嘛，我应该……"。
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    /** 改写/意图这类小任务用它，避免等待过长。 */
    private static final Duration SMALL_TASK_TIMEOUT = Duration.ofSeconds(8);
    /** 工具决策比"改写一句话"重（要读 4 个工具定义 + 最近几轮），给宽一点，否则会白丢一次调用。 */
    private static final Duration TOOL_DECISION_TIMEOUT = Duration.ofSeconds(15);
    /** 等响应头（模型开始回话）的上限：排队久也不该超过这个。 */
    private static final Duration FIRST_BYTE_TIMEOUT = Duration.ofSeconds(15);

    private final RuntimeSettings models;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WebClient webClient;

    /**
     * 是否让模型输出思考过程。
     *
     * <p>默认关闭：实测同一个模型开着思考做「改写一句话」要 22 秒（702 个 reasoning token），
     * 关掉只要 4 秒。陪伴对话每轮还有改写 + 意图两次小调用，开着思考首字要等一分钟，
     * 那是不可用的。要回复更"深思"一点，把 {@code LLM_THINKING=true} 打开即可。
     */
    private final boolean thinkingEnabled;

    public LlmClient(RuntimeSettings models,
            @Value("${seeky.model.llm.thinking-enabled:}") String thinkingFlag) {
        this.models = models;
        // 用字符串接再自己判，不用 boolean 直接绑：.env 里写成 LLM_THINKING=（空值）时，
        // Spring 对 "" → boolean 的转换会给出让人意外的结果，而这个开关一旦判错就是
        // "流式只回思考过程、正文永远等不到"，现象是每轮都卡满超时 —— 排查成本极高。
        this.thinkingEnabled = "true".equalsIgnoreCase(thinkingFlag == null ? "" : thinkingFlag.strip());
        this.webClient = buildWebClient();
        log.info("[llm] 模型 {}，思考过程={}", models.defaults().llm().model(), thinkingEnabled ? "开启" : "关闭");
    }

    /**
     * 建 WebClient：必须自己管连接池与响应超时。
     *
     * <p>默认池的 idle 连接永不过期，网关那边静默断开的连接还会被复用——表现就是请求发出去、
     * 一个字节都不回，最后卡满应用层超时（本地实测过：同一条请求用 curl 秒回，走 Java 卡死 60 秒）。
     * 这里把 idle/寿命/pending 都限死，再加 {@code responseTimeout} 兜住"连上了但不回"。
     */
    private WebClient buildWebClient() {
        ConnectionProvider provider = ConnectionProvider.builder("llm-pool")
                .maxConnections(48)
                .pendingAcquireMaxCount(96)
                .pendingAcquireTimeout(Duration.ofSeconds(10))
                .maxIdleTime(Duration.ofSeconds(20))
                .maxLifeTime(Duration.ofMinutes(5))
                .evictInBackground(Duration.ofSeconds(10))
                .build();
        HttpClient httpClient = HttpClient.create(provider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .responseTimeout(Duration.ofSeconds(60))
                .keepAlive(true);
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    /** 这次请求能不能真的去调模型（配了 Key 且不是演示协议）。 */
    public boolean configured(EffectiveSettings s) {
        return s.llm().configured() && !s.llm().mock();
    }

    public String model(EffectiveSettings s) {
        return s.llm().model();
    }

    /**
     * 流式生成：逐 token 吐出正文。
     *
     * <p>为什么要显式 {@code Connection: close}、还要在"一个字都没吐出来"时重试一次：
     * 实测过一个很坑的现象 —— 同样的请求体用 curl 秒回，走 Java 偶尔一个字节都收不到，
     * 卡满超时，日志里连响应头都没出现过。原因在连接复用：上一轮 SSE 是被客户端提前取消的，
     * 这条连接在网关那边已经废了但本地池还留着，下一轮骑上去就再也没有下文。
     * 所以①每轮让服务端主动关连接，②首包 15 秒不来就换条连接重开一次。
     * <b>只有 {@code emitted == 0} 才重试</b> —— 已经吐了一半再重来，用户会看到同一句话两遍。
     *
     * @param messages OpenAI 格式的消息列表（system / user / assistant）
     */
    public Flux<String> stream(List<Map<String, String>> messages, EffectiveSettings s) {
        if (!configured(s)) {
            return Flux.just(mockReply(messages));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model(s));
        body.put("messages", messages);
        body.put("stream", true);
        body.put("temperature", 0.85);
        applyThinking(body);

        return Flux.defer(() -> {
            AtomicInteger emitted = new AtomicInteger();
            return openStream(body, s)
                    .doOnNext(chunk -> emitted.incrementAndGet())
                    .retryWhen(Retry.max(1)
                            .filter(ex -> emitted.get() == 0 && !(ex instanceof LlmException)));
        }).onErrorResume(ex -> {
            log.warn("[llm] 流式生成失败: {}", ex.getMessage());
            return Flux.empty();
        });
    }

    /** 一次开流尝试：只取正文增量，思考过程（reasoning_content）直接丢弃。 */
    private Flux<String> openStream(Map<String, Object> body, EffectiveSettings s) {
        return webClient.post()
                .uri(url(s))
                .header("Authorization", "Bearer " + s.llm().apiKey())
                .header("Connection", "close")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .exchangeToFlux(resp -> {
                    if (resp.statusCode().isError()) {
                        return resp.bodyToMono(String.class).defaultIfEmpty("")
                                .flatMapMany(raw -> Flux.error(new LlmException(
                                        "HTTP " + resp.statusCode().value() + "：" + shorten(raw))));
                    }
                    // 有这一行日志，就能分清"请求没发出去/没回响应头"和"连上了但没吐字"
                    log.info("[llm] 流已建立 status={} type={}", resp.statusCode().value(),
                            resp.headers().asHttpHeaders().getContentType());
                    return resp.bodyToFlux(String.class);
                })
                .map(this::payloadOf)
                .takeWhile(line -> !line.isBlank() && !"[DONE]".equals(line))
                .mapNotNull(this::deltaOf)
                // 放在最后：量的是"两次吐字之间的静默"，模型开口之后不该再有这么长的停顿
                .timeout(FIRST_BYTE_TIMEOUT);
    }

    /** 非流式生成：改写、意图、记忆抽取这类"要一个结果"的调用。失败返回空字符串，由调用方回退。 */
    public Mono<String> complete(List<Map<String, String>> messages, double temperature,
            EffectiveSettings s) {
        if (!configured(s)) {
            return Mono.just(mockReply(messages));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model(s));
        body.put("messages", messages);
        body.put("stream", false);
        body.put("temperature", temperature);
        applyThinking(body);

        return webClient.post()
                .uri(url(s))
                .header("Authorization", "Bearer " + s.llm().apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                        .map(raw -> new LlmException("HTTP " + resp.statusCode().value() + "：" + shorten(raw))))
                .bodyToMono(String.class)
                .timeout(SMALL_TASK_TIMEOUT)
                .map(this::contentOf)
                .defaultIfEmpty("")
                .onErrorResume(ex -> {
                    log.warn("[llm] 调用失败: {}", ex.getMessage());
                    return Mono.just("");
                });
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 带工具的一次调用（非流式）：让模型决定要不要动手。
     *
     * <p>为什么工具决策不做流式：OpenAI 兼容协议里 tool_calls 是分片拼出来的，边流边执行要自己
     * 攒增量、还要处理"参数还没收全就想跑"，收益只是提前几百毫秒。这里做成"先决策、再开口"：
     * 这一轮先问要不要调工具（一次小请求），调完再让输出节点正常流式说话。
     */
    public Mono<ToolTurn> withTools(List<Map<String, String>> messages,
            List<Map<String, Object>> tools, double temperature, EffectiveSettings s) {
        return withTools(messages, tools, null, temperature, s);
    }

    /**
     * 带工具的一次调用，并可**强制**指定调哪个。
     *
     * <p>{@code forcedTool} 非空时传 {@code tool_choice={"type":"function",...}}：意图已经判定
     * 这一轮是「设提醒」这类动作，就不该再让模型自由决定"要不要动手" —— 它一旦犯懒不调，
     * 用户得到的就是"好的我记住了"加一条根本不存在的提醒。
     *
     * @param forcedTool 必须调用的工具名；null 表示让模型自己决定（auto）
     */
    public Mono<ToolTurn> withTools(List<Map<String, String>> messages,
            List<Map<String, Object>> tools, String forcedTool, double temperature, EffectiveSettings s) {
        if (!configured(s) || tools == null || tools.isEmpty()) {
            return Mono.just(new ToolTurn("", List.of()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model(s));
        body.put("messages", messages);
        body.put("tools", tools);
        body.put("tool_choice", forcedTool == null || forcedTool.isBlank()
                ? "auto"
                : Map.of("type", "function", "function", Map.of("name", forcedTool)));
        body.put("stream", false);
        body.put("temperature", temperature);
        applyThinking(body);

        return webClient.post()
                .uri(url(s))
                .header("Authorization", "Bearer " + s.llm().apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                        .map(raw -> new LlmException("HTTP " + resp.statusCode().value() + "：" + shorten(raw))))
                .bodyToMono(String.class)
                .timeout(TOOL_DECISION_TIMEOUT)
                .map(this::toolTurnOf)
                .onErrorResume(ex -> {
                    log.warn("[llm] 工具决策失败（按不调工具继续）: {}", ex.getMessage());
                    return Mono.just(new ToolTurn("", List.of()));
                });
    }

    /** 模型这一轮的答复：文本 + 它想调的工具。 */
    public record ToolTurn(String content, List<ToolCall> calls) {
        public boolean hasCalls() {
            return calls != null && !calls.isEmpty();
        }
    }

    /** 一次函数调用；{@code arguments} 是模型给的 JSON 字符串，可能不合法。 */
    public record ToolCall(String id, String name, String arguments) {
    }

    private ToolTurn toolTurnOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ToolTurn("", List.of());
        }
        try {
            JsonNode message = objectMapper.readTree(raw).path("choices").path(0).path("message");
            String content = message.path("content").asText("");
            List<ToolCall> calls = new ArrayList<>();
            JsonNode array = message.path("tool_calls");
            if (array.isArray()) {
                for (JsonNode item : array) {
                    String name = item.path("function").path("name").asText("");
                    if (name.isBlank()) {
                        continue;
                    }
                    calls.add(new ToolCall(item.path("id").asText(""), name,
                            item.path("function").path("arguments").asText("{}")));
                }
            }
            return new ToolTurn(content, calls);
        } catch (Exception ex) {
            log.warn("[llm] 工具响应解析失败: {}", shorten(raw));
            return new ToolTurn("", List.of());
        }
    }

    /** 火山方舟/多数 OpenAI 兼容网关都认这个字段；不认的网关会直接忽略，不影响请求。 */
    private void applyThinking(Map<String, Object> body) {
        if (!thinkingEnabled) {
            body.put("thinking", Map.of("type", "disabled"));
        }
    }

    private String url(EffectiveSettings s) {
        String base = s.llm().baseUrl();
        String root = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return root + "/chat/completions";
    }

    /** SSE 行：Spring 解出来的可能已去掉 {@code data:} 前缀，两种都兼容。 */
    private String payloadOf(String raw) {
        if (raw == null) {
            return "";
        }
        String line = raw.trim();
        if (line.startsWith("data:")) {
            line = line.substring(5).trim();
        }
        return line;
    }

    /** 取增量正文；只认 content，跳过 reasoning_content 与空块。 */
    private String deltaOf(String payload) {
        if (payload.isBlank() || "[DONE]".equals(payload)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return null;
            }
            JsonNode delta = choices.get(0).path("delta");
            JsonNode content = delta.get("content");
            if (content == null || content.isNull()) {
                return null;
            }
            String text = content.asText();
            return text == null || text.isEmpty() ? null : text;
        } catch (Exception ex) {
            // 解析不了的块直接丢，不要因为一个坏块中断整段回复
            return null;
        }
    }

    private String contentOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode content = root.path("choices").path(0).path("message").get("content");
            return content == null || content.isNull() ? "" : content.asText().trim();
        } catch (Exception ex) {
            log.warn("[llm] 响应解析失败: {}", shorten(raw));
            return "";
        }
    }

    /**
     * 没配 Key 时的演示回复：不做任何 AI 推理，只保证链路能跑通、前端有字看。
     * 真正的 mock 语义在各节点里（改写回退原句、意图默认 chat）。
     */
    private String mockReply(List<Map<String, String>> messages) {
        String last = messages.isEmpty() ? "" : messages.get(messages.size() - 1).getOrDefault("content", "");
        return "（演示模式）我听到你说「" + (last.length() > 20 ? last.substring(0, 20) + "…" : last) + "」了。";
    }

    private String shorten(String raw) {
        if (raw == null) {
            return "";
        }
        String one = raw.replaceAll("\\s+", " ");
        return one.length() > 200 ? one.substring(0, 200) + "…" : one;
    }

    /** 便于上层区分"模型调用失败"和其它异常。 */
    public static final class LlmException extends RuntimeException {
        public LlmException(String message) {
            super(message);
        }
    }

    /** 拼接消息的小工具，节点里反复要用。 */
    public static List<Map<String, String>> messages(String system, List<Map<String, String>> history,
            String user) {
        List<Map<String, String>> list = new ArrayList<>();
        if (system != null && !system.isBlank()) {
            list.add(Map.of("role", "system", "content", system));
        }
        if (history != null) {
            list.addAll(history);
        }
        if (user != null && !user.isBlank()) {
            list.add(Map.of("role", "user", "content", user));
        }
        return list;
    }
}
