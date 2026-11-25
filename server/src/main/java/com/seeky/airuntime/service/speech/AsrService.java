package com.seeky.airuntime.service.speech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seeky.airuntime.common.BusinessException;
import com.seeky.airuntime.common.ErrorCode;
import com.seeky.airuntime.config.AsrProperties;
import com.seeky.airuntime.config.ModelProperties;
import com.seeky.airuntime.config.EffectiveSettings;
import com.seeky.airuntime.config.RuntimeSettings;
import com.seeky.airuntime.dto.Transcript;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/**
 * 语音转文字（ASR）：豆包「录音文件识别 · 极速版」。
 *
 * <p>为什么用极速版而不是 ai-runtime 那套：ai-runtime 的 {@code SpeechToTextService} 走的是
 * 百炼「录音文件识别」异步接口，{@code input.file_urls} 要求音频有**公网可访问 URL**
 * （典型是 OSS），浏览器这边只有一段 Blob 的 Base64，根本没有 URL 可给。极速版
 * {@code /api/v3/auc/bigmodel/recognize/flash} 直接把音频字节放进请求体、同步返回文字，
 * 形态与前端 {@code Composer.jsx} 的「Base64 一发、马上要文字」完全对齐。
 *
 * <p>沿用 ai-runtime 的几处做法，不重复踩坑：
 * <ul>
 *   <li><b>状态码看 header</b>：火山的成败在 {@code X-Api-Status-Code}（成功 {@code 20000000}），
 *       HTTP 200 不代表识别成功；</li>
 *   <li><b>错误体截断</b>：下游报文最多留 300 字符，避免把整段 JSON 甩到界面上；</li>
 *   <li><b>静音不是错误</b>：{@code 20000003}（没有有效人声）返回空文本，交给前端提示"没听清"，
 *       而不是弹一个"识别失败"的通红提示；</li>
 *   <li><b>ITN + 标点</b>：{@code enable_itn/enable_punc} 打开，「六点八」→「6.8」，聊天里更像人话。</li>
 * </ul>
 *
 * <p>录音格式：浏览器 MediaRecorder 出的是 webm/opus 或 mp4，火山不认 webm 容器，
 * 所以前端先转成 16kHz 单声道 WAV 再发（见 {@code front/src/recorder.js}），这里只做兜底映射。
 */
@Service
public class AsrService {

    private static final Logger log = LoggerFactory.getLogger(AsrService.class);

    /** 极速版识别入口（同步，一段音频直接回文字）。 */
    private static final String FLASH_PATH = "/api/v3/auc/bigmodel/recognize/flash";

    /** 火山成功码：不是 0，也不是 200。 */
    private static final int CODE_OK = 20_000_000;

    /** 没有有效人声：不算失败，返回空文本。 */
    private static final int CODE_NO_SPEECH = 20_000_003;

    private static final String PROTOCOL = "volcengine";

    /**
     * 方舟多模态协议：走 Chat Completions，把音频当作 {@code input_audio} 内容块发进去。
     *
     * <p>为什么加这条路：火山「语音技术」的录音文件识别要单独申请 openspeech 的 Key，
     * 而方舟的 Agent Plan 端点里 {@code doubao-seed-2-1-lite} 本身就听得懂音频 ——
     * 一把现有的 {@code ark-} Key 就能转写，不用再开一套账号体系。
     * 实测：16kHz 单声道 WAV 说「感冒了怎么办」，返回就是「感冒了怎么办」。
     */
    private static final String PROTOCOL_ARK = "ark";

    /**
     * 转写提示词。要点是"只输出文字"：不加解释、不翻译、不补全，
     * 否则模型会回「这段话的意思是……」这类东西，直接变成用户说的话就荒唐了。
     */
    private static final String ARK_PROMPT = """
            你是语音转写器。把这段音频里说的话逐字转成文字。
            只输出文字本身：不要解释、不要加引号、不要补全、不要翻译、不要加标点以外的任何内容。
            如果音频里没有可识别的人声，只输出空字符串。""";

    /** 模型把"没听到"说成人话时的几种常见形态，统一当空处理。 */
    private static final java.util.Set<String> EMPTY_MARKERS = java.util.Set.of(
            "无", "空", "（空）", "(空)", "没有内容", "无内容", "听不清", "音频中没有有效人声");

    private static final int MAX_ERROR_BODY_LENGTH = 300;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 演示模式的固定识别结果：没配真实服务时也能把「录音 → 发送 → 回复语音」整条链走完。 */
    private static final String MOCK_TEXT = "今天有点冷，你出门多穿件衣服";

    /** 只用于启动日志里的默认值；识别时一律用请求带来的 {@link EffectiveSettings}。 */
    private final RuntimeSettings defaults;
    private final AsrProperties properties;
    private final WebClient webClient;

    public AsrService(RuntimeSettings defaults, AsrProperties properties) {
        this.defaults = defaults;
        this.properties = properties;
        ConnectionProvider provider = ConnectionProvider.builder("asr")
                .maxIdleTime(Duration.ofSeconds(15))
                .evictInBackground(Duration.ofSeconds(5))
                .build();
        HttpClient httpClient = HttpClient.create(provider)
                .responseTimeout(properties.timeoutOrDefault())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000);
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
        log.info("[asr] 语音识别：protocol={} model={} resource={}",
                defaults.defaults().asr().protocol(), defaults.defaults().asr().model(), properties.resourceIdOrDefault());
    }

    /** 能不能用：mock 也算能用；真实协议要求地址 / Key / 模型齐。 */
    public boolean configured(EffectiveSettings s) {
        return s.asr().configured();
    }

    /**
     * 识别一段录音。
     *
     * @param audioBase64 音频的 Base64（不含 data URI 前缀）
     * @param format      容器格式：wav / mp3 / m4a / ogg（前端转好 WAV 后传 wav）
     * @param durationMs  录音时长，仅用于日志与"太短"的判断
     */
    public Mono<Transcript> transcribe(String audioBase64, String format, Long durationMs,
            EffectiveSettings s) {
        ModelProperties.Endpoint endpoint = s.asr();
        if (!endpoint.enabled()) {
            return Mono.error(new BusinessException(ErrorCode.ASR_ERROR, "语音识别被禁用（ASR_ENABLED=false）"));
        }
        if (endpoint.mock()) {
            return Mono.just(new Transcript(MOCK_TEXT, true, 0, "mock"));
        }
        if (!endpoint.configured()) {
            return Mono.error(new BusinessException(ErrorCode.ASR_ERROR,
                    "语音识别还没配置，缺少：" + endpoint.missing()));
        }
        if (audioBase64 == null || audioBase64.isBlank()) {
            return Mono.error(new BusinessException(ErrorCode.PARAM_MISSING, "audioBase64 不能为空"));
        }
        // 计时器必须在协议分发之前：ark 与 volcengine 两条路都要算延迟
        long started = System.currentTimeMillis();
        if (PROTOCOL_ARK.equalsIgnoreCase(endpoint.protocol())) {
            return transcribeArk(audioBase64, formatOf(format), endpoint, started);
        }
        if (!PROTOCOL.equalsIgnoreCase(endpoint.protocol())) {
            return Mono.error(new BusinessException(ErrorCode.ASR_ERROR,
                    "不支持的 ASR 协议 " + endpoint.protocol() + "，只支持 ark / volcengine / mock"));
        }

        String formatOrDefault = formatOf(format);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user", Map.of("uid", properties.userIdOrDefault()));
        body.put("audio", Map.of("data", audioBase64, "format", formatOrDefault));
        body.put("request", Map.of(
                "model_name", endpoint.model().isBlank() ? "bigmodel" : endpoint.model(),
                "enable_itn", true,
                "enable_punc", true));

        log.info("[asr] 开始识别: format={} chars={} duration={}ms",
                formatOrDefault, audioBase64.length(), durationMs);

        return webClient.post()
                .uri(endpoint.baseUrl() + FLASH_PATH)
                .header("X-Api-Key", endpoint.apiKey())
                .header("X-Api-Resource-Id", properties.resourceIdOrDefault())
                .header("X-Api-Request-Id", UUID.randomUUID().toString())
                .header("X-Api-Sequence", "-1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(resp -> resp.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .<Transcript>map(raw -> handleResponse(resp.statusCode(),
                                resp.headers().asHttpHeaders().getFirst("X-Api-Status-Code"),
                                resp.headers().asHttpHeaders().getFirst("X-Api-Message"),
                                raw, endpoint.model(), started)))
                .timeout(properties.timeoutOrDefault())
                .doOnNext(t -> log.info("[asr] 识别完成: chars={} 耗时 {}ms", t.text().length(), t.latencyMs()))
                .onErrorMap(ex -> ex instanceof BusinessException ? ex
                        : new BusinessException(ErrorCode.ASR_ERROR, "语音识别调用失败：" + ex.getMessage()));
    }

    /** 解析火山响应：成败看 header，正文里取 {@code result.text}。 */
    private Transcript handleResponse(HttpStatusCode httpStatus, String statusCode, String apiMessage,
            String raw, String model, long started) {
        long latency = System.currentTimeMillis() - started;
        int code = parseCode(statusCode);
        if (!httpStatus.is2xxSuccessful() || (code != CODE_OK && code != CODE_NO_SPEECH)) {
            throw new BusinessException(ErrorCode.ASR_ERROR,
                    "语音识别失败（" + (statusCode == null ? "HTTP " + httpStatus.value() : statusCode) + "）"
                            + (apiMessage == null || apiMessage.isBlank() ? "：" + shorten(raw) : "：" + apiMessage));
        }
        String text = textOf(raw);
        if (text.isBlank()) {
            // 20000003 = 没听到有效人声；不是错误，返回空文本让前端说"没听清"
            log.info("[asr] 没有有效人声（status={}）", statusCode);
            return Transcript.empty(model, latency);
        }
        return new Transcript(text, false, latency, model);
    }

    /** 正文优先 {@code result.text}；没有就拼 {@code result.utterances[].text}。 */
    private static String textOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            JsonNode root = JSON.readTree(raw);
            JsonNode result = root.path("result");
            String text = result.path("text").asText("");
            if (!text.isBlank()) {
                return text.strip();
            }
            JsonNode utterances = result.path("utterances");
            if (utterances.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode item : utterances) {
                    String line = item.path("text").asText("");
                    if (!line.isBlank()) {
                        sb.append(line.strip());
                    }
                }
                return sb.toString().strip();
            }
            return "";
        } catch (Exception ex) {
            log.warn("[asr] 响应解析失败：{}（原文：{}）", ex.getMessage(), shorten(raw));
            return "";
        }
    }

    /** MIME / 扩展名 / 裸格式名都认：audio/wav、audio/webm;codecs=opus、wav → wav。 */
    private static String formatOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return "wav";
        }
        String value = raw.strip().toLowerCase();
        int semicolon = value.indexOf(';');
        if (semicolon > 0) {
            value = value.substring(0, semicolon);
        }
        return switch (value) {
            case "audio/wav", "audio/x-wav", "audio/wave" -> "wav";
            case "audio/mpeg", "audio/mp3" -> "mp3";
            case "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac" -> "m4a";
            case "audio/ogg", "audio/opus" -> "ogg";
            default -> value.contains("/") ? value.substring(value.indexOf('/') + 1) : value;
        };
    }

    private static int parseCode(String header) {
        try {
            return header == null || header.isBlank() ? -1 : Integer.parseInt(header.strip());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static String shorten(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= MAX_ERROR_BODY_LENGTH ? flat : flat.substring(0, MAX_ERROR_BODY_LENGTH) + "…";
    }

    /**
     * 方舟多模态转写：一段 Base64 音频 → 一句文字。
     *
     * <p>和火山录音识别那条路的差别：那边成败看 {@code X-Api-Status-Code} 响应头，
     * 这边是标准 OpenAI 形态 —— HTTP 非 2xx 就是失败，正文在 {@code error.message}。
     */
    private Mono<Transcript> transcribeArk(String audioBase64, String format,
            ModelProperties.Endpoint endpoint, long started) {
        Map<String, Object> audio = new LinkedHashMap<>();
        audio.put("data", audioBase64);
        audio.put("format", format);

        Map<String, Object> audioPart = new LinkedHashMap<>();
        audioPart.put("type", "input_audio");
        audioPart.put("input_audio", audio);

        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", ARK_PROMPT);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", List.of(audioPart, textPart));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", endpoint.model());
        body.put("messages", List.of(message));

        String url = stripTrailingSlash(endpoint.baseUrl()) + "/chat/completions";
        log.info("[asr] 走方舟转写: model={} format={} chars={} duration={}ms",
                endpoint.model(), format, audioBase64.length(), 0);

        return webClient.post()
                .uri(url)
                .header("Authorization", "Bearer " + endpoint.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(resp -> resp.bodyToMono(String.class).defaultIfEmpty("")
                        .map(raw -> {
                            long latency = System.currentTimeMillis() - started;
                            if (!resp.statusCode().is2xxSuccessful()) {
                                throw new BusinessException(ErrorCode.ASR_ERROR,
                                        "语音识别失败（HTTP " + resp.statusCode().value() + "）："
                                                + errorMessageOf(raw));
                            }
                            String text = cleanTranscript(arkTextOf(raw));
                            if (text.isBlank()) {
                                log.info("[asr] 方舟未识别出有效人声");
                                return Transcript.empty(endpoint.model(), latency);
                            }
                            return new Transcript(text, false, latency, endpoint.model());
                        }))
                .timeout(properties.timeoutOrDefault())
                .onErrorMap(ex -> ex instanceof BusinessException ? ex
                        : new BusinessException(ErrorCode.ASR_ERROR, "语音识别调用失败：" + ex.getMessage()));
    }

    /** {@code choices[0].message.content}，字符串或内容块数组都认。 */
    static String arkTextOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            JsonNode root = JSON.readTree(raw);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isTextual()) {
                return content.asText("");
            }
            if (content.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode part : content) {
                    if (part.path("type").asText("").equals("text")) {
                        sb.append(part.path("text").asText(""));
                    }
                }
                return sb.toString();
            }
            return "";
        } catch (Exception ex) {
            log.warn("[asr] 方舟响应解析失败：{}（原文：{}）", ex.getMessage(), shorten(raw));
            return "";
        }
    }

    private static String errorMessageOf(String raw) {
        try {
            String message = JSON.readTree(raw).path("error").path("message").asText("");
            return message.isBlank() ? shorten(raw) : shorten(message);
        } catch (Exception ex) {
            return shorten(raw);
        }
    }

    /** 去掉模型爱加的外层引号，并把"没有内容"的各种说法归一成空串。 */
    static String cleanTranscript(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.strip()
                .replaceAll("^[\"'\u201c\u201d\u2018\u2019]+", "")
                .replaceAll("[\"'\u201c\u201d\u2018\u2019]+$", "")
                .strip();
        if (EMPTY_MARKERS.contains(text)) {
            return "";
        }
        return text;
    }

    private static String stripTrailingSlash(String url) {
        String value = url == null ? "" : url.strip();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /** 供设置页「测试连接」用：不带音频，只校验配置是否齐全。 */
    public Map<String, Object> describe(EffectiveSettings s) {
        ModelProperties.Endpoint endpoint = s.asr();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("configured", endpoint.configured());
        body.put("mock", endpoint.mock());
        body.put("protocol", endpoint.protocol());
        body.put("model", endpoint.model());
        body.put("resourceId", properties.resourceIdOrDefault());
        body.put("missing", endpoint.missing());
        body.put("formats", List.of("wav", "mp3", "m4a", "ogg"));
        return body;
    }
}
