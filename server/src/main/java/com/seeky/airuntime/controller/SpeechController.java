package com.seeky.airuntime.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seeky.airuntime.common.BusinessException;
import com.seeky.airuntime.common.ErrorCode;
import com.seeky.airuntime.common.Result;
import com.seeky.airuntime.config.ClientConfig;
import com.seeky.airuntime.config.EffectiveSettings;
import com.seeky.airuntime.config.RuntimeSettings;
import com.seeky.airuntime.dto.SpeechRequest;
import com.seeky.airuntime.dto.Transcript;
import com.seeky.airuntime.dto.TtsAudio;
import com.seeky.airuntime.dto.TtsRequest;
import com.seeky.airuntime.service.speech.AsrService;
import com.seeky.airuntime.service.speech.TtsService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 语音接口。
 *
 * <ul>
 *   <li>{@code POST /api/v1/speech/tts}：整段合成，返回 {@code Result<TtsAudio>}。
 *       前端按句切分后一次一句（{@code front/src/voice.js}），拿到就能播 —— 主要用这个。</li>
 *   <li>{@code POST /api/v1/speech/tts/stream}：SSE 流式合成，帧序
 *       {@code start → audio*(seq 递增，Base64 MP3 片段) → done}，失败补 {@code error} 帧后结束。
 *       给以后「边生成边播」用，帧协议与 seeky-ai-runtime 完全一致。</li>
 *   <li>{@code POST /api/v1/speech/asr}：语音识别（豆包极速版，Base64 直传、同步出文字）。未配置时
 *       明确提示「未配置」，而不是一个莫名其妙的 404。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/speech")
public class SpeechController {

    private static final Logger log = LoggerFactory.getLogger(SpeechController.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final TtsService tts;
    private final AsrService asr;
    private final RuntimeSettings settings;

    public SpeechController(TtsService tts, AsrService asr, RuntimeSettings settings) {
        this.tts = tts;
        this.asr = asr;
        this.settings = settings;
    }

    /** 客户端随请求带上来的配置；没带就用 .env 那份。 */
    private EffectiveSettings effective(String clientHeader) {
        return settings.merge(ClientConfig.parse(clientHeader));
    }

    /**
     * 文字转语音（整段）。前端 {@code voice.js} 的契约：
     * {@code {audioBase64, contentType}}，其余字段是调试用的元信息。
     */
    @PostMapping("/tts")
    public Mono<Result<TtsAudio>> textToAudio(
            @RequestHeader(value = ClientConfig.HEADER, required = false) String clientHeader,
            @RequestBody(required = false) TtsRequest request) {
        if (request == null || request.text() == null || request.text().isBlank()) {
            return Mono.error(new BusinessException(ErrorCode.PARAM_MISSING, "text 不能为空"));
        }
        return tts.synthesizeAll(request.text(), request.voice(), effective(clientHeader)).map(Result::ok);
    }

    /**
     * 文字转语音（SSE 流式）。帧协议同 ai-runtime：
     * <ul>
     *   <li>{@code start}：{@code {model, voice, format, sampleRate}}；</li>
     *   <li>{@code audio}：{@code {seq, data}}，data 为 Base64 片段，按 seq 拼即完整文件；</li>
     *   <li>{@code done}：{@code {chunks, bytes, characters}}；</li>
     *   <li>{@code error}：{@code {code, errMsg}}。</li>
     * </ul>
     */
    @PostMapping(value = "/tts/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> textToAudioStream(
            @RequestHeader(value = ClientConfig.HEADER, required = false) String clientHeader,
            @RequestBody(required = false) TtsRequest request) {
        EffectiveSettings effective = effective(clientHeader);
        String text = request == null || request.text() == null ? "" : request.text();
        return Flux.defer(() -> {
            AtomicInteger seq = new AtomicInteger();
            AtomicLong bytes = new AtomicLong();
            int characters = text.isBlank() ? 0
                    : text.strip().codePointCount(0, text.strip().length());
            Flux<ServerSentEvent<String>> frames = tts.synthesize(text, request.voice(), effective)
                    .concatMap(chunk -> {
                        if (chunk.hasAudio()) {
                            int index = seq.getAndIncrement();
                            bytes.addAndGet(decodedLength(chunk.audioBase64()));
                            return Mono.just(frame("audio", audioPayload(index, chunk.audioBase64())));
                        }
                        if (chunk.terminal()) {
                            return Mono.just(frame("done", donePayload(seq.get(), bytes.get(), characters)));
                        }
                        return Mono.empty();
                    })
                    .onErrorResume(ex -> {
                        log.error("[tts] 流式合成失败: chars={}, sentChunks={}, sentBytes={}, msg={}",
                                characters, seq.get(), bytes.get(), ex.getMessage());
                        return Mono.just(frame("error", errorPayload(ex)));
                    });
            return Flux.concat(Mono.just(frame("start", startPayload(effective))), frames);
        });
    }

    /**
     * 语音识别：录音的 Base64 直接进来，文字直接回去（豆包极速版，同步）。
     *
     * <p>返回 {@code {text, mock, latencyMs, model}}；{@code mock=true} 表示没配真实服务、
     * 用的是演示文本，前端可以据此弱化展示。
     */
    @PostMapping("/asr")
    public Mono<Result<Transcript>> transcribe(
            @RequestHeader(value = ClientConfig.HEADER, required = false) String clientHeader,
            @RequestBody(required = false) SpeechRequest request) {
        if (request == null || request.audioBase64() == null || request.audioBase64().isBlank()) {
            return Mono.error(new BusinessException(ErrorCode.PARAM_MISSING, "audioBase64 不能为空"));
        }
        return asr.transcribe(request.audioBase64(), request.mimeType(), request.durationMs(),
                        effective(clientHeader))
                .map(Result::ok);
    }

    /**
     * 音色试听/设置页用：只回「能不能用」和音色。
     *
     * <p>刻意不回 protocol / model / baseUrl：服务端那份配置是后端的事，
     * 下发给前端没意义，还有把内部网关地址暴露出去的风险。
     */
    @GetMapping("/tts/config")
    public Result<Map<String, Object>> ttsConfig(
            @RequestHeader(value = ClientConfig.HEADER, required = false) String clientHeader) {
        EffectiveSettings effective = effective(clientHeader);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("configured", tts.configured(effective));
        body.put("mock", effective.tts().mock());
        body.put("voice", tts.voiceOrDefault(effective));
        return Result.ok(body);
    }

    private Map<String, Object> startPayload(EffectiveSettings effective) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("voice", tts.voiceOrDefault(effective));
        return data;
    }

    private static Map<String, Object> audioPayload(int seq, String base64) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("seq", seq);
        data.put("data", base64);
        return data;
    }

    private static Map<String, Object> donePayload(int chunks, long bytes, int characters) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("chunks", chunks);
        data.put("bytes", bytes);
        data.put("characters", characters);
        return data;
    }

    private static Map<String, Object> errorPayload(Throwable ex) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", ex instanceof BusinessException business
                ? business.getCode()
                : ErrorCode.SPEECH_ERROR);
        data.put("errMsg", ex.getMessage() == null ? "语音合成失败" : ex.getMessage());
        return data;
    }

    private static ServerSentEvent<String> frame(String event, Map<String, Object> data) {
        try {
            return ServerSentEvent.<String>builder()
                    .event(event)
                    .data(OBJECT_MAPPER.writeValueAsString(data))
                    .build();
        } catch (Exception ex) {
            throw new IllegalStateException("SSE 帧序列化失败", ex);
        }
    }

    /** Base64 解码后的字节数（不真正解码，按长度公式估算，去掉补位符）。 */
    private static long decodedLength(String base64) {
        String value = base64.strip();
        int padding = value.endsWith("==") ? 2 : value.endsWith("=") ? 1 : 0;
        return (long) value.length() * 3 / 4 - padding;
    }
}
