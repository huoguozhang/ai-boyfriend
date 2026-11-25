package com.seeky.airuntime.service.chat;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seeky.airuntime.config.EffectiveSettings;
import com.seeky.airuntime.config.RuntimeSettings;
import com.seeky.airuntime.service.chat.nodes.FanOutNode;
import com.seeky.airuntime.service.chat.nodes.IntentNode;
import com.seeky.airuntime.service.chat.nodes.MemoryNode;
import com.seeky.airuntime.service.chat.nodes.OutputNode;
import com.seeky.airuntime.service.chat.nodes.PersonaNode;
import com.seeky.airuntime.service.chat.nodes.QueryRewriteNode;
import com.seeky.airuntime.service.chat.nodes.ToolNode;
import com.seeky.airuntime.service.chat.nodes.common.StateKeys;
import com.seeky.airuntime.service.memory.MemoryWriteService;
import com.seeky.airuntime.store.ChatStore;
import com.seeky.airuntime.store.UserStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SynchronousSink;
import reactor.core.scheduler.Schedulers;

/**
 * 主对话编排（对应 ai-runtime 的 {@code SeekyMainBotService}）。
 *
 * <pre>
 * START → 改写 → 意图 → 分派 ┬→ 记忆召回 ┐
 *                            └→ 角色画像 ┴→ 输出 → END
 * </pre>
 *
 * <p>建图方式与 ai-runtime 一致：同一套 {@code StateGraph} + {@code KeyStrategy.REPLACE} +
 * 显式空 {@code SaverConfig}（不显式传会自动挂 MemorySaver，把每轮状态留在内存里）。
 * 扇出用图的边，不是 {@code Promise.all} —— 记忆与画像本来就没有先后依赖，交给图去并行。
 *
 * <p>SSE 帧：{@code text-delta} / {@code notice} / {@code done} / {@code error}，
 * 与前端 {@code useChat.js} 的事件处理一一对应。
 */
@Service
public class SoulmateBotService {

    private static final Logger log = LoggerFactory.getLogger(SoulmateBotService.class);
    private static final String GRAPH_NAME = "soulmate_chat";

    private final CompiledGraph graph;
    private final ChatStore store;
    /** 取用户的称呼（注册时填的昵称）：每轮读一次，改昵称立刻生效，不用清缓存。 */
    private final UserStore users;
    /** .env 默认值：只在请求没带配置时兜底（历史条数这类）。 */
    private final RuntimeSettings defaults;
    private final MemoryWriteService memoryWrite;
    private final String outputNodeId;
    private final String toolNodeId;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SoulmateBotService(QueryRewriteNode rewriteNode, IntentNode intentNode,
            FanOutNode fanOutNode, MemoryNode memoryNode, PersonaNode personaNode,
            ToolNode toolNode, OutputNode outputNode, ChatStore store, UserStore users,
            RuntimeSettings defaults, MemoryWriteService memoryWrite) {
        this.store = store;
        this.users = users;
        this.defaults = defaults;
        this.memoryWrite = memoryWrite;
        this.outputNodeId = outputNode.id();
        this.toolNodeId = toolNode.id();
        try {
            StateGraph graph = new StateGraph(GRAPH_NAME,
                    KeyStrategy.builder().defaultStrategy(KeyStrategy.REPLACE).build());
            graph.addNode(rewriteNode.id(), rewriteNode.action());
            graph.addNode(intentNode.id(), intentNode.action());
            graph.addNode(fanOutNode.id(), fanOutNode.action());
            graph.addNode(memoryNode.id(), memoryNode.action());
            graph.addNode(personaNode.id(), personaNode.action());
            graph.addNode(toolNode.id(), toolNode.action());
            graph.addNode(outputNode.id(), outputNode.action());

            graph.addEdge(StateGraph.START, rewriteNode.id());
            graph.addEdge(rewriteNode.id(), intentNode.id());
            graph.addEdge(intentNode.id(), fanOutNode.id());
            // 扇出：记忆、画像、工具三路并发，各自直接连到输出
            graph.addEdge(fanOutNode.id(), memoryNode.id());
            graph.addEdge(fanOutNode.id(), personaNode.id());
            graph.addEdge(fanOutNode.id(), toolNode.id());
            graph.addEdge(memoryNode.id(), outputNode.id());
            graph.addEdge(personaNode.id(), outputNode.id());
            graph.addEdge(toolNode.id(), outputNode.id());
            graph.addEdge(outputNode.id(), StateGraph.END);

            this.graph = graph.compile(CompileConfig.builder()
                    .saverConfig(SaverConfig.builder().build())
                    .build());
            log.info("[chat] 对话图已编译：{}", GRAPH_NAME);
        } catch (GraphStateException ex) {
            // 图画错了属于启动期错误，宁可起不来也不要带着一张坏图接请求
            throw new IllegalStateException("对话图构建失败：" + ex.getMessage(), ex);
        }
    }

    /**
     * 处理一轮对话。
     *
     * @param sessionId 会话 id（前端生成并持有）
     * @param userId    用户维度，记忆按它隔离
     * @param text      用户输入
     * @param settings  本次请求的生效配置（.env 默认 + 客户端随请求带上来的覆盖）
     * @param fromClient 大模型这份是不是客户端自己填的（只影响报错时怎么提示）
     */
    public Flux<ServerSentEvent<String>> chat(String sessionId, String userId, String text,
            String mediaJson, EffectiveSettings settings, boolean fromClient) {
        // 归属校验先做（ensureSession 内部会拦别人的 sessionId，抛 4403），
        // 再取历史 —— 顺序反了的话，越权请求会先读到别人的消息才被拒。
        // 取历史要在落库之前：这样喂给模型的 history 里不含刚发的这句，避免 query 出现两遍
        store.ensureSession(sessionId, userId);
        List<Map<String, String>> history = store.recent(sessionId, userId, settings.prefs().historyLimit());
        // mediaJson 带着「这条是语音 + 多长」：不存的话，刷新页面后语音气泡会退化成文字，
        // 时长也变 0（前端历史是按 media 还原 kind 的）
        store.append(sessionId, userId, "user", text, mediaJson);

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put(StateKeys.QUERY, text);
        inputs.put(StateKeys.USER_ID, userId);
        // 称呼来自 users 表（注册时填的昵称），不是记忆 —— 所以每轮现读一次，
        // 用户改了昵称下一句就生效，也不会因为 mem0 抽取失败而丢
        inputs.put(StateKeys.USER_NAME, callName(userId));
        inputs.put(StateKeys.SESSION_ID, sessionId);
        inputs.put(StateKeys.HISTORY, history);
        // 配置随这一轮的状态走：各节点从这里取，谁都不回头读全局
        inputs.put(StateKeys.SETTINGS, settings);

        StringBuilder answer = new StringBuilder();
        // 工具节点这一轮如果已经主动记过事，异步抽取就跳过：两条路都写会留下重复记忆
        java.util.concurrent.atomic.AtomicBoolean toolRemembered =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        return graph.stream(inputs)
                // 图是阻塞式编排（中间节点要等 LLM 返回一个值再往下走），必须离开 Netty 的
                // IO 线程：在 reactor-http-nio-* 上调用 block 会被 Reactor 直接拒绝
                // （"block()/blockFirst()/blockLast() are blocking"）。弹性池就是给这种场景用的。
                .subscribeOn(Schedulers.boundedElastic())
                .ofType(StreamingOutput.class)
                .doOnNext(output -> log.info("[chat] 帧 node={} type={} len={}",
                        output.node(), output.getOutputType(),
                        output.chunk() == null ? 0 : output.chunk().length()))
                .filter(output -> output.getOutputType() == OutputType.GRAPH_NODE_STREAMING)
                // 只有输出节点与工具节点会推东西：前者是字，后者是"发图/记下来"这类指令帧
                .filter(output -> outputNodeId.equals(output.node()) || toolNodeId.equals(output.node()))
                // 用 handle 而不是 map：chunk 可能是 null（框架会推空块），
                // map 返回 null 会直接抛 NPE，把整轮回复打断
                .handle((StreamingOutput output, SynchronousSink<ServerSentEvent<String>> sink) -> {
                    String chunk = output.chunk();
                    if (chunk == null || chunk.isEmpty()) {
                        return;
                    }
                    if (outputNodeId.equals(output.node())) {
                        answer.append(chunk);
                        sink.next(frame("text-delta", Map.of("delta", chunk)));
                        return;
                    }
                    // 工具帧：私有的 {event,data} 一行 JSON，原样翻成 SSE（media / notice / memory-updated…）
                    ToolFrame.parse(chunk).ifPresent(f -> {
                        if ("memory-updated".equals(f.event())) {
                            toolRemembered.set(true);
                        }
                        sink.next(frame(f.event(), f.data()));
                    });
                })
                .concatWith(Mono.defer(() -> {
                    String finalText = answer.toString().trim();
                    if (finalText.isEmpty()) {
                        return Mono.just(frame("error", lmFailure(fromClient)));
                    }
                    String messageId = store.append(sessionId, userId, "assistant", finalText, null);
                    // 记：把这轮丢给异步抽取，抽取失败也不影响已经发出去的回复。
                    // 但她这一轮已经用 remember 工具记过了就不再抽 —— 两条路都写，记忆页会出现
                    // 好几条同义条目（实测「不吃香菜」一次留了三条）
                    if (toolRemembered.get()) {
                        log.info("[chat] 本轮已由工具写入记忆，跳过异步抽取");
                    } else {
                        memoryWrite.extractAndSave(userId, sessionId, text, finalText, settings)
                                .subscribe(count -> {
                                    if (count > 0) {
                                        log.info("[chat] 本轮新增 {} 条记忆", count);
                                    }
                                }, ex -> log.warn("[chat] 记忆抽取失败: {}", ex.getMessage()));
                    }
                    return Mono.just(frame("done", Map.of("text", finalText, "messageId", messageId)));
                }))
                .onErrorResume(ex -> {
                    log.warn("[chat] 本轮失败: {}", ex.getMessage(), ex);
                    String reason = ex.getMessage() == null ? "" : ex.getMessage();
                    boolean looksLikeLlm = fromClient
                            || reason.contains("HTTP 4") || reason.contains("HTTP 5");
                    return Mono.just(frame("error", looksLikeLlm
                            ? lmFailure(fromClient)
                            : Map.of("message", "这轮没接上，再说一次试试",
                                    "code", 5100, "detail", reason)));
                });
    }

    /**
     * 她该管他叫什么：注册时填的昵称；昵称空就退回用户名；连用户行都读不到就返回空串
     * （提示词那边会退化成不称呼，不影响这一轮回复）。
     *
     * <p>为什么放在这里而不是做成一个图节点：它是一次主键查询，几毫秒的事，
     * 单独一个节点要多一轮调度、多一份状态，收益不成比例。
     */
    private String callName(String userId) {
        try {
            return users.findById(userId)
                    .map(user -> {
                        String nickname = user.nickname();
                        return nickname == null || nickname.isBlank() ? user.username() : nickname;
                    })
                    .orElse("");
        } catch (RuntimeException ex) {
            // 读库失败不该让整轮对话废掉：没有称呼也能聊
            log.warn("[chat] 读取用户称呼失败 userId={}: {}", userId, ex.getMessage());
            return "";
        }
    }

    /**
     * 大模型这轮没出字时的提示。
     *
     * <p>带上 {@code source}：前端据此决定文案 —— 客户端自己填的配置出错，得明确告诉用户
     * 「是你填的 baseUrl / Key / 模型名有问题」，而不是让他去猜服务端。
     */
    private Map<String, Object> lmFailure(boolean fromClient) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", fromClient
                ? "你填的大模型配置没通（baseUrl / API Key / 模型名检查一下）"
                : "她说不出话来了，检查一下大模型配置");
        body.put("code", 6000);
        body.put("service", "llm");
        body.put("source", fromClient ? "client" : "server");
        return body;
    }

    /** 一条 SSE 帧：event 名 + JSON data（前端按 event 分支处理）。 */
    private ServerSentEvent<String> frame(String event, Map<String, Object> data) {
        String json;
        try {
            json = objectMapper.writeValueAsString(data);
        } catch (Exception ex) {
            json = "{}";
        }
        return ServerSentEvent.<String>builder().event(event).data(json).build();
    }
}
