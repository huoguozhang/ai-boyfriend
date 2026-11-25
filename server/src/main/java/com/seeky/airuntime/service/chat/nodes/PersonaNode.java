package com.seeky.airuntime.service.chat.nodes;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.seeky.airuntime.config.EffectiveSettings;
import com.seeky.airuntime.config.RuntimeSettings;
import com.seeky.airuntime.service.chat.nodes.common.GraphNode;
import com.seeky.airuntime.service.chat.nodes.common.StateKeys;
import java.util.List;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 画像节点：把她自己的设定整理成一小段文本。
 *
 * <p>为什么不直接在输出节点的提示词里写死：角色卡是可以在设置页改的（名字、城市、职业、宠物），
 * 这些值只该有一处来源（{@code CompanionProperties}）。这里把它转成一句人话交给模型，
 * 比在 13 个占位符里各填一次更不容易漏。
 *
 * <p>用户自己的画像（他住哪、做什么）不在这里 —— 那些是记忆，由 {@link MemoryNode} 从 mem0 取。
 */
@Component
public class PersonaNode implements GraphNode {

    /** .env 默认值；本次请求的真实配置从状态里取（客户端传了就用客户端的）。 */
    private final RuntimeSettings defaults;

    public PersonaNode(RuntimeSettings defaults) {
        this.defaults = defaults;
    }

    @Override
    public String id() {
        return "persona";
    }

    @Override
    public String name() {
        return "persona_profile";
    }

    @Override
    public String label() {
        return "角色画像";
    }

    @Override
    public String type() {
        return "agent";
    }

    @Override
    public String role() {
        return "profile";
    }

    @Override
    public String description() {
        return "把角色卡整理成文本块";
    }

    @Override
    public List<String> inputKeys() {
        return List.of(StateKeys.INTENT);
    }

    @Override
    public String outputKey() {
        return StateKeys.PERSONA;
    }

    @Override
    public Flux<String> stream(OverAllState state) {
        EffectiveSettings settings = settingsOf(state, defaults);
        StringBuilder sb = new StringBuilder();
        sb.append("她叫").append(settings.name())
                .append("，").append(settings.age()).append(" 岁，")
                .append(settings.city()).append("的").append(settings.job()).append("。");
        if (settings.petName() != null && !settings.petName().isBlank()) {
            sb.append("她养了只猫叫").append(settings.petName()).append("。");
        }
        return Flux.just(sb.toString());
    }
}
