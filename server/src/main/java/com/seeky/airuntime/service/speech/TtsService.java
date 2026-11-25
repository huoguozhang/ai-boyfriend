package com.seeky.airuntime.service.speech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seeky.airuntime.common.BusinessException;
import com.seeky.airuntime.common.ErrorCode;
import com.seeky.airuntime.config.EffectiveSettings;
import com.seeky.airuntime.config.ModelProperties;
import com.seeky.airuntime.config.RuntimeSettings;
import com.seeky.airuntime.config.TtsProperties;
import com.seeky.airuntime.dto.TtsAudio;
import com.seeky.airuntime.dto.TtsChunk;
import io.netty.channel.ChannelOption;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/**
 * 文字转语音（TTS），走<b>火山引擎豆包语音合成</b>的 HTTP 单向流式接口。
 *
 * <p>用的是方舟 Agent Plan 的专属 Key（和 {@code LLM_API_KEY} 同一个），不需要另外开通
 * 豆包语音的 appid / access_token：
 * <ul>
 *   <li>{@code POST https://openspeech.bytedance.com/api/v3/plan/tts/unidirectional}；</li>
 *   <li>header {@code X-Api-Key}（专属 Key）+ {@code X-Api-Resource-Id: seed-tts-2.0}
 *       （2.0 音色必须用 2.0 的 resource id，否则会 45000000 音色鉴权失败）；</li>
 *   <li>请求体 {@code {"user":{"uid"},"req_params":{"text","speaker","audio_params":{...}}}}；</li>
 *   <li>响应是 <b>换行分隔的 JSON 包</b>（不是裸二进制）：每包
 *       {@code {"code":0,"data":"<base64 音频>"}}，中间可能夹一包
 *       {@code data=null, sentence:{...}}（时间戳），最后一包 {@code code=20000000} 表示结束；
 *       把各包的 data 依次 base64 解码拼起来就是完整 MP3。</li>
 * </ul>
 *
 * <p>两个出口：{@link #synthesize} 给流式接口（逐片转发），{@link #synthesizeAll} 给前端
 * {@code /api/v1/speech/tts}（一句一次、整段返回）。音频不落磁盘、不入库 —— 缓存交给前端
 * （{@code voice.js} 已有 LRU）。
 *
 * <p>连接池单独一份：公网网关会单方面回收空闲 keep-alive，复用死连接会在读响应时直接 RST
 * （踩过，见 {@code LlmClient} 的注释）。这里限死空闲寿命，并且只在「一片音频都还没出来」时重试一次。
 */
@Service
public class TtsService {

    private static final Logger log = LoggerFactory.getLogger(TtsService.class);

    /** 合成结束码：火山用 20000000 表示成功结束（不是 0）。 */
    private static final int CODE_FINISHED = 20_000_000;

    private static final String PROTOCOL = "volcengine";

    private static final int MAX_ERROR_BODY_LENGTH = 300;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 只用于启动日志里的那行默认值；真正合成时一律用请求带来的 {@link EffectiveSettings}。 */
    private final RuntimeSettings defaults;
    private final TtsProperties properties;
    private final WebClient webClient;

    public TtsService(RuntimeSettings defaults, TtsProperties properties) {
        this.defaults = defaults;
        this.properties = properties;
        ConnectionProvider provider = ConnectionProvider.builder("tts")
                .maxIdleTime(Duration.ofSeconds(15))
                .evictInBackground(Duration.ofSeconds(5))
                .build();
        HttpClient httpClient = HttpClient.create(provider)
                .responseTimeout(properties.responseTimeoutOrDefault())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000);
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
        log.info("[tts] 语音合成：protocol={} model={} voice={} resource={} format={} {}Hz {}bps 上限{}字",
                defaults.defaults().tts().protocol(), defaults.defaults().tts().model(), voiceOrDefault(defaults.defaults()),
                properties.resourceIdOrDefault(), properties.formatOrDefault(),
                properties.sampleRateOrDefault(), properties.bitRateOrDefault(),
                properties.maxTextLengthOrDefault());
    }

    /** 能不能用：mock 也算能用（出提示音），真实协议要求地址/Key/模型齐。 */
    public boolean configured(EffectiveSettings s) {
        return s.tts().configured();
    }

    public String voiceOrDefault(EffectiveSettings s) {
        String voice = s.tts().voice();
        return voice == null || voice.isBlank() ? TtsProperties.DEFAULT_VOICE : voice;
    }

    /**
     * 流式合成：每片音频一个 {@link TtsChunk}，终帧 {@code terminal=true}。
     *
     * @param text  待合成文本（调用方已按句切分）
     * @param voice 音色，空则用配置默认值
     */
    public Flux<TtsChunk> synthesize(String text, String voice, EffectiveSettings s) {
        String normalized = text == null ? "" : text.strip();
        if (normalized.isEmpty()) {
            return Flux.error(new BusinessException(ErrorCode.PARAM_MISSING, "text 不能为空"));
        }
        int chars = normalized.codePointCount(0, normalized.length());
        int maxLength = properties.maxTextLengthOrDefault();
        if (chars > maxLength) {
            return Flux.error(new BusinessException(ErrorCode.PARAM_VALIDATION_FAILED,
                    "文本长度 " + chars + " 超过上限 " + maxLength + "，请按句切分后再合成"));
        }
        String effectiveVoice = voice == null || voice.isBlank() ? voiceOrDefault(s) : voice.strip();
        if (!s.tts().configured()) {
            return Flux.error(new BusinessException(ErrorCode.CAPABILITY_NOT_CONFIGURED,
                    "语音合成未配置：" + s.tts().missing()));
        }
        if (s.tts().mock()) {
            return mock(normalized);
        }
        if (!PROTOCOL.equalsIgnoreCase(s.tts().protocol())) {
            return Flux.error(new BusinessException(ErrorCode.MODEL_CONFIG_ERROR,
                    "不支持的 TTS 协议 " + s.tts().protocol() + "，只支持 volcengine / mock"));
        }

        Map<String, Object> audioParams = new LinkedHashMap<>();
        audioParams.put("format", properties.formatOrDefault());
        audioParams.put("sample_rate", properties.sampleRateOrDefault());
        audioParams.put("bit_rate", properties.bitRateOrDefault());
        audioParams.put("enable_timestamp", false);
        audioParams.put("enable_subtitle", false);

        Map<String, Object> reqParams = new LinkedHashMap<>();
        reqParams.put("text", normalized);
        reqParams.put("speaker", effectiveVoice);
        reqParams.put("audio_params", audioParams);

        // uid 只用于服务端日志追溯，不做鉴权，传业务侧的固定用户标识即可
        Map<String, Object> payload = Map.of(
                "user", Map.of("uid", "local-user"),
                "req_params", reqParams);

        long startedAt = System.nanoTime();
        // 是否已经吐出过音频：重试只允许发生在「一片都还没合成出来」的时候
        java.util.concurrent.atomic.AtomicBoolean emitted = new java.util.concurrent.atomic.AtomicBoolean();
        log.info("[tts] 开始合成: chars={}, speaker={}, preview={}", chars, effectiveVoice,
                abbreviate(normalized, 40));

        return webClient.post()
                .uri(s.tts().baseUrl())
                .header("X-Api-Key", s.tts().apiKey())
                .header("X-Api-Resource-Id", properties.resourceIdOrDefault())
                .header("X-Api-Request-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_PLAIN)
                .bodyValue(payload)
                .exchangeToFlux(response -> {
                    if (response.statusCode().isError()) {
                        return response.bodyToMono(String.class).defaultIfEmpty("")
                                .flatMapMany(body -> {
                                    log.warn("[tts] 火山返回 HTTP {}: {}", response.statusCode().value(),
                                            abbreviate(body, MAX_ERROR_BODY_LENGTH));
                                    return Flux.error(new BusinessException(ErrorCode.SPEECH_ERROR,
                                            "语音合成失败（HTTP " + response.statusCode().value() + "）："
                                                    + abbreviate(extractMessage(body), MAX_ERROR_BODY_LENGTH)));
                                });
                    }
                    // 响应是 chunked 的换行分隔 JSON。注意 Spring 的 String 解码本身可能已经按行切过，
                    // 也可能把整段一次给出，所以这里不再假设边界：统一用 Jackson 把一段文本里的
                    // 多个 JSON 依次读出来（只解析第一个会静默丢掉后面的音频）
                    return response.bodyToFlux(String.class)
                            .concatMap(line -> Flux.fromIterable(splitJsonObjects(line)));
                })
                .concatMap(TtsService::parseLine)
                .doOnNext(chunk -> {
                    if (chunk.hasAudio()) {
                        emitted.set(true);
                    }
                })
                .timeout(properties.overallTimeoutOrDefault())
                .retryWhen(reactor.util.retry.Retry.backoff(1, Duration.ofMillis(300))
                        .filter(ex -> !emitted.get() && !(ex instanceof BusinessException)))
                .doOnComplete(() -> log.info("[tts] 合成完成: chars={}, 耗时 {}ms", chars,
                        (System.nanoTime() - startedAt) / 1_000_000))
                .onErrorMap(ex -> !(ex instanceof BusinessException),
                        ex -> new BusinessException(ErrorCode.SPEECH_ERROR,
                                "语音合成服务调用失败：" + ex.getMessage()));
    }

    /**
     * 整段合成：拼成一个完整音频返回给前端。
     *
     * <p>文本超长时按标点切段再顺序合成（片段字节直接拼接，MP3 帧首尾相接，听不出切过），
     * 避免模型偶尔回一大段没标点的话时整条播报直接失败。
     */
    public Mono<TtsAudio> synthesizeAll(String text, String voice, EffectiveSettings s) {
        String normalized = text == null ? "" : text.strip();
        if (normalized.isEmpty()) {
            return Mono.error(new BusinessException(ErrorCode.PARAM_MISSING, "text 不能为空"));
        }
        int chars = normalized.codePointCount(0, normalized.length());
        long startedAt = System.nanoTime();
        return Flux.fromIterable(splitByLimit(normalized, properties.maxTextLengthOrDefault()))
                .concatMap(part -> synthesize(part, voice, s))
                .collect(ByteArrayOutputStream::new, (out, chunk) -> {
                    if (chunk.hasAudio()) {
                        out.writeBytes(decode(chunk.audioBase64()));
                    }
                })
                .map(out -> {
                    byte[] audio = out.toByteArray();
                    if (audio.length == 0) {
                        throw new BusinessException(ErrorCode.SPEECH_ERROR, "语音合成返回空音频");
                    }
                    log.info("[tts] 整段合成: chars={}, bytes={}, 耗时 {}ms", chars, audio.length,
                            (System.nanoTime() - startedAt) / 1_000_000);
                    return new TtsAudio(
                            Base64.getEncoder().encodeToString(audio),
                            properties.contentType(),
                            properties.formatOrDefault(),
                            voice == null || voice.isBlank() ? voiceOrDefault(s) : voice.strip(),
                            chars,
                            audio.length,
                            s.tts().mock());
                });
    }

    /**
     * 把一段文本里连续排列的多个 JSON 对象拆成一行一个。
     *
     * <p>为什么要这么写：火山响应是「换行分隔的 JSON 包」，但 WebClient 的 String 解码
     * 在部分情况下按行切、部分情况下整段给出。之前自己按 {@code \n} 攒 buffer，碰上
     * 「已按行切过」的情况就永远攒不到换行，最后把十几包拼成一行，Jackson 只解析第一个
     * JSON → 音频只剩 0.3 秒。用 parser 逐个读值，两种切法都对。
     */
    static List<String> splitJsonObjects(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        try (com.fasterxml.jackson.core.JsonParser parser = OBJECT_MAPPER.createParser(text)) {
            while (parser.nextToken() != null) {
                JsonNode node = OBJECT_MAPPER.readTree(parser);
                if (node == null || node.isMissingNode()) {
                    break;
                }
                out.add(node.toString());
            }
        } catch (Exception ex) {
            // 拆不开（报文本身坏了）就整段交给 parseLine，让它按格式异常报错
            return List.of(text);
        }
        return out.isEmpty() ? List.of(text) : out;
    }

    /**
     * 解析一包：{@code code=20000000} 是结束；{@code code!=0} 是错误；
     * {@code data} 非空就是一段音频；只有 {@code sentence} 的包（时间戳）直接跳过。
     */
    private static Mono<TtsChunk> parseLine(String line) {
        if (line == null || line.isBlank()) {
            return Mono.empty();
        }
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(line);
        } catch (Exception ex) {
            return Mono.error(new BusinessException(ErrorCode.SPEECH_ERROR,
                    "语音合成响应格式异常：" + abbreviate(line, MAX_ERROR_BODY_LENGTH)));
        }
        int code = root.path("code").asInt(0);
        String message = root.path("message").asText("");
        if (code == CODE_FINISHED) {
            return Mono.just(TtsChunk.end());
        }
        if (code != 0) {
            String detail = code + (message.isBlank() ? "" : " " + message);
            log.warn("[tts] 火山返回错误码: {}", abbreviate(detail, MAX_ERROR_BODY_LENGTH));
            return Mono.error(new BusinessException(ErrorCode.SPEECH_ERROR, "语音合成失败：" + detail));
        }
        String data = root.path("data").asText("");
        if (data.isBlank()) {
            return Mono.empty();
        }
        return Mono.just(TtsChunk.audio(data));
    }

    /**
     * 把超长文本按标点切成每段不超过 {@code limit} 个码点。
     *
     * <p>切点优先句末（。！？；…～换行），其次逗号，实在没有标点才硬切 —— 硬切会切在词中间，
     * 听感上会有一个短停顿，但总比整段报错好。用 {@code offsetByCodePoints} 是为了不把 emoji
     * 的代理对切一半（那会变成乱码）。
     */
    static List<String> splitByLimit(String text, int limit) {
        if (limit <= 0 || text.codePointCount(0, text.length()) <= limit) {
            return List.of(text);
        }
        List<String> parts = new ArrayList<>();
        int start = 0;
        int length = text.length();
        while (start < length) {
            int remaining = text.codePointCount(start, length);
            int take = Math.min(limit, remaining);
            int end = text.offsetByCodePoints(start, take);
            if (end >= length) {
                parts.add(text.substring(start));
                break;
            }
            int cut = lastBreak(text, start, end);
            if (cut <= start) {
                cut = end;
            }
            parts.add(text.substring(start, cut));
            start = cut;
        }
        return parts;
    }

    /** 在 [start, end) 里找最后一个句末标点（含逗号兜底），返回切点（标点之后）。 */
    private static int lastBreak(String text, int start, int end) {
        for (int strength = 0; strength < 2; strength++) {
            for (int i = end - 1; i >= start; i--) {
                char c = text.charAt(i);
                boolean hit = strength == 0
                        ? "。！？!?；;…～\n".indexOf(c) >= 0
                        : "，、,".indexOf(c) >= 0;
                if (hit) {
                    return i + 1;
                }
            }
        }
        return -1;
    }

    /**
     * 演示音频：没配真实 TTS 时出一段 120ms 的柔和提示音（16bit / 16kHz 单声道 WAV）。
     *
     * <p>刻意不做「假装在说话」的静音：一听就知道是占位，不会让人误以为合成成功了。
     */
    private static Flux<TtsChunk> mock(String text) {
        int sampleRate = 16_000;
        int toneMs = 120;
        int samples = sampleRate * toneMs / 1000;
        int dataLength = samples * 2;
        byte[] wav = new byte[44 + dataLength];
        writeAscii(wav, 0, "RIFF");
        writeIntLe(wav, 4, 36 + dataLength);
        writeAscii(wav, 8, "WAVEfmt ");
        writeIntLe(wav, 16, 16);          // fmt 块长度
        writeShortLe(wav, 20, 1);         // PCM
        writeShortLe(wav, 22, 1);         // 单声道
        writeIntLe(wav, 24, sampleRate);
        writeIntLe(wav, 28, sampleRate * 2); // 字节率
        writeShortLe(wav, 32, 2);         // 块对齐
        writeShortLe(wav, 34, 16);        // 位深
        writeAscii(wav, 36, "data");
        writeIntLe(wav, 40, dataLength);
        for (int i = 0; i < samples; i++) {
            double envelope = Math.sin(Math.PI * i / samples); // 淡入淡出，避免爆音
            short value = (short) (Math.sin(2 * Math.PI * 440 * i / sampleRate) * 6000 * envelope);
            wav[44 + i * 2] = (byte) (value & 0xFF);
            wav[45 + i * 2] = (byte) ((value >> 8) & 0xFF);
        }
        log.info("[tts] 演示模式（未配置真实 TTS），返回 {} 字对应的提示音，{} 字节", text.length(), wav.length);
        return Flux.just(TtsChunk.audio(Base64.getEncoder().encodeToString(wav)), TtsChunk.end());
    }

    private static void writeAscii(byte[] buf, int offset, String value) {
        for (int i = 0; i < value.length(); i++) {
            buf[offset + i] = (byte) value.charAt(i);
        }
    }

    private static void writeIntLe(byte[] buf, int offset, int value) {
        buf[offset] = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
        buf[offset + 2] = (byte) ((value >> 16) & 0xFF);
        buf[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static void writeShortLe(byte[] buf, int offset, int value) {
        buf[offset] = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private static byte[] decode(String base64) {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.SPEECH_ERROR, "语音合成返回的音频不是合法 Base64");
        }
    }

    /** 错误体可读信息：{"code","message"} / {"error":{"message"}}；非 JSON 原样返回。 */
    private static String extractMessage(String body) {
        if (body == null || body.isBlank()) {
            return "下游未返回错误信息";
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(body);
            for (JsonNode node : List.of(root.path("error"), root, root.path("output"))) {
                String message = node.path("message").asText("");
                if (!message.isBlank()) {
                    return codeWithMessage(root) + message;
                }
            }
        } catch (Exception ignored) {
            // 非 JSON，原样返回
        }
        return body;
    }

    private static String codeWithMessage(JsonNode root) {
        int code = root.path("code").asInt(0);
        return code == 0 ? "" : code + " ";
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "...";
    }
}
