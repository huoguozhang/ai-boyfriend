package com.seeky.airuntime.service.chat.nodes;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.seeky.airuntime.service.chat.nodes.common.GraphNode;
import com.seeky.airuntime.service.chat.nodes.common.StateKeys;
import java.util.List;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 分派节点：没有业务逻辑，只作为扇出三条边（记忆 / 画像 / 媒体）的共同起点。
 *
 * <p>为什么留一个空节点而不是直接从意图节点连三条边：扇出需要一个"三路都已经可以开跑"的
 * 汇合点，否则加一条支路就要改上游节点的连线；ai-runtime 也是这么做的。
 */
@Component
public class FanOutNode implements GraphNode {

    @Override
    public String id() {
        return "fanOut";
    }

    @Override
    public String name() {
        return "fan_out";
    }

    @Override
    public String label() {
        return "并行分派";
    }

    @Override
    public String type() {
        return "router";
    }

    @Override
    public String role() {
        return "dispatcher";
    }

    @Override
    public String description() {
        return "把记忆、画像、媒体三路并行拉起";
    }

    @Override
    public List<String> inputKeys() {
        return List.of(StateKeys.INTENT);
    }

    @Override
    public String outputKey() {
        return "fanOutFlag";
    }

    @Override
    public Flux<String> stream(OverAllState state) {
        return Flux.just("ok");
    }
}
