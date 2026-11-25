package com.seeky.airuntime.controller;

import com.seeky.airuntime.auth.AuthContext;
import com.seeky.airuntime.common.BusinessException;
import com.seeky.airuntime.config.ClientConfig;
import com.seeky.airuntime.config.EffectiveSettings;
import com.seeky.airuntime.config.RuntimeSettings;
import com.seeky.airuntime.dto.ChatRequest;
import com.seeky.airuntime.service.chat.SoulmateBotService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;

/**
 * 对话接口：{@code POST /api/v1/chat/stream}，SSE 流式返回。
 *
 * <p>不用 {@code Result} 信封：SSE 的每一帧自己就是消息单元，外面再套一层
 * {@code {success,data}} 前端就得先解信封再解帧，白白多一步。错误通过 {@code error} 帧表达。
 *
 * <p><b>用户身份只来自 token</b>：请求体里的 {@code userId} 已废弃（留着字段是为了老前端不报错，
 * 值一律忽略）。会话归属由 {@code ChatStore.ensureSession} 校验，拿别人的 sessionId 会得到 4403。
 */
@RestController
public class ChatController {

    private final SoulmateBotService bot;
    private final RuntimeSettings settings;

    public ChatController(SoulmateBotService bot, RuntimeSettings settings) {
        this.bot = bot;
        this.settings = settings;
    }

    @PostMapping(value = "/api/v1/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            ServerWebExchange exchange,
            @RequestHeader(value = ClientConfig.HEADER, required = false) String clientHeader,
            @RequestBody ChatRequest request) {
        String userId = AuthContext.requireUserId(exchange);
        String text = request.text() == null ? "" : request.text().trim();
        String sessionId = request.sessionIdOr("");
        if (text.isEmpty()) {
            return errorFrame("说点什么吧", 4202);
        }
        if (sessionId.isEmpty()) {
            // 正常流程走不到：bootstrap 一定会给一个 sessionId。
            // 走 SSE 而不是抛异常，是因为这里的返回类型是事件流，抛出去会变成 500 而不是一帧 error。
            return errorFrame("缺少会话，请刷新页面重试", 4202);
        }
        // 客户端在设置页填的模型 / Key 从这里进来；没填的字段自动回落到 .env
        ClientConfig client = ClientConfig.parse(clientHeader);
        EffectiveSettings effective = settings.merge(client);
        boolean fromClient = !client.services().isEmpty()
                && client.services().containsKey("llm");
        // defer：让 bot.chat 里的同步异常（比如 4403 会话不属于你）发生在订阅期，
        // 这样能翻成一帧 error 交给前端弹提示；直接在方法体里抛会变成一条 JSON 响应，
        // 前端按 SSE 解析时什么也读不到（静默失败）。
        // 语音消息把「时长」一起落库：前端刷新后靠它还原成语音气泡而不是变回文字
        String mediaJson = mediaJsonOf(request);
        return Flux.defer(() -> bot.chat(sessionId, userId, text, mediaJson, effective, fromClient))
                .onErrorResume(BusinessException.class,
                        ex -> errorFrame(ex.getMessage(), ex.getCode()));
    }

    /**
     * 抽出语音消息的附加信息。只认 {@code inputMode=voice}，其余一律 null ——
     * 文字消息不需要往库里多塞一份空 JSON。
     */
    private static String mediaJsonOf(ChatRequest request) {
        if (request == null || !"voice".equalsIgnoreCase(request.inputMode())) {
            return null;
        }
        Object raw = request.audio() == null ? null : request.audio().get("durationMs");
        long durationMs = raw instanceof Number number ? Math.max(0L, number.longValue()) : 0L;
        return "{\"inputMode\":\"voice\",\"audio\":{\"durationMs\":" + durationMs + "}}";
    }

    private static Flux<ServerSentEvent<String>> errorFrame(String message, int code) {
        String safe = message == null ? "" : message.replace("\\", "\\\\").replace("\"", "\\\"");
        return Flux.just(ServerSentEvent.<String>builder()
                .event("error")
                .data("{\"message\":\"" + safe + "\",\"code\":" + code + "}")
                .build());
    }
}
