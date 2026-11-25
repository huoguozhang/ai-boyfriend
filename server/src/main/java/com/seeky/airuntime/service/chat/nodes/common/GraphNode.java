package com.seeky.airuntime.service.chat.nodes.common;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.streaming.GraphFlux;
import com.seeky.airuntime.config.EffectiveSettings;
import com.seeky.airuntime.config.RuntimeSettings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import reactor.core.publisher.Flux;

/**
 * 图节点契约（照 ai-runtime 的 {@code GraphNode} 裁剪）。
 *
 * <p>保留了它真正起作用的四件事：身份（id/name/type/role/description）、
 * {@link #stream} 产出、{@link #outputKey} 写回哪个状态键、以及默认把产出包成
 * {@link GraphFlux} 的 {@link #action()} 模板 —— 这样图执行时 {@code graph.stream()}
 * 里能拿到 {@code StreamingOutput}，逐帧推给前端。
 *
 * <p>去掉了 ai-runtime 的 {@code GraphNodeRunState} 埋点与 Langfuse 观测：那套依赖
 * 采集器注册表和 traceparent 透传，本 Demo 没有观测后端，留着只是空转。
 *
 * <p>与 ai-runtime 的一个差别：{@code stream()} 返回 {@code Flux<String>} 而不是
 * {@code Flux<ChatResponse>}。那边接的是 Spring AI 的 ChatModel，本来就在吐 ChatResponse；
 * 我们直连 OpenAI 兼容 HTTP，硬包一层 ChatResponse 只为了再拆回字符串，没有意义。
 */
public interface GraphNode {

    /** 图内唯一标识，addNode / 连边 / 前端标识都用它。 */
    String id();

    String name();

    /** 日志里的中文名。 */
    default String label() {
        return name();
    }

    /** agent / router / output —— 只用于日志与调试展示。 */
    String type();

    String role();

    String description();

    /** 本节点消费的状态键（调试日志用）。 */
    default List<String> inputKeys() {
        return List.of(StateKeys.QUERY);
    }

    /** 本节点写回的状态键。 */
    String outputKey();

    /**
     * 是否以 {@link GraphFlux} 的形式写回状态。
     *
     * <p>只有真正要"边生成边推给用户"的节点（输出节点）才为 true。中间节点必须返回 false：
     * GraphFlux 写进状态后，下游 {@code state.value(key, "")} 拿到的是 {@code GraphResponse}
     * 包装对象而不是字符串，强转就会抛 ClassCastException（这个坑实测踩过一次）。
     */
    default boolean streaming() {
        return false;
    }

    /** 核心逻辑：产出本节点的输出流。 */
    Flux<String> stream(OverAllState state);

    /**
     * 默认动作：
     * <ul>
     *   <li>普通节点 —— 同步取回 {@link #stream} 的第一个值，作为**普通字符串**写回状态，
     *       下游读到的就是真实内容；</li>
     *   <li>流式节点 —— 包成 {@link GraphFlux}，由框架逐块转成 {@code StreamingOutput}。</li>
     * </ul>
     * 阻塞取值的代价可以接受：这些节点的流本身就是一次 HTTP 调用（内部已带超时），
     * 而图执行本身跑在独立的 parallel 线程池里，不会卡住 WebFlux 的 IO 线程。
     */
    default AsyncNodeAction action() {
        return AsyncNodeAction.node_async(state -> {
            Map<String, Object> result = new LinkedHashMap<>();
            if (streaming()) {
                result.put(outputKey(), GraphFlux.of(id(), outputKey(), stream(state)));
            } else {
                String value = stream(state).blockFirst(BLOCK_TIMEOUT);
                result.put(outputKey(), value == null ? "" : value);
            }
            return result;
        });
    }

    /** 中间节点的取值上限：比内部 HTTP 超时（5~8s）留足余量，超过就是真出问题了。 */
    java.time.Duration BLOCK_TIMEOUT = java.time.Duration.ofSeconds(30);

    /**
     * 本次请求的生效配置：优先用状态里那份（客户端传了就用客户端的），
     * 没有就回落到 .env 默认值。
     */
    default EffectiveSettings settingsOf(OverAllState state, RuntimeSettings defaults) {
        Optional<EffectiveSettings> found = state.value(StateKeys.SETTINGS);
        return found.filter(EffectiveSettings.class::isInstance)
                .map(EffectiveSettings.class::cast)
                .orElseGet(defaults::defaults);
    }

    /** 流异常结束（默认只记日志，由节点自行覆盖）。 */
    default void onErr(Throwable ex) {
    }

    /** 流正常结束。 */
    default void onFinish() {
    }
}
