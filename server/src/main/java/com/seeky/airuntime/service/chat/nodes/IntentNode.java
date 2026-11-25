package com.seeky.airuntime.service.chat.nodes;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seeky.airuntime.config.EffectiveSettings;
import com.seeky.airuntime.config.RuntimeSettings;
import com.seeky.airuntime.prompt.PromptCatalog;
import com.seeky.airuntime.prompt.PromptKeys;
import com.seeky.airuntime.service.chat.LlmClient;
import com.seeky.airuntime.service.chat.nodes.common.GraphNode;
import com.seeky.airuntime.service.chat.nodes.common.StateKeys;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 意图识别：chat / emotion / ask_media / ask_memory / smalltalk / remind_set / remind_query / remind_cancel。
 *
 * <p>这些类别的真正分界线是「问答」还是「动作」：前五个只影响这一轮读什么、怎么说话；
 * 后三个是要动手写数据的 Action（落一条真实的提醒），所以下游工具节点按它切换工具集。
 *
 * <p>意图只影响「这一轮要不要读记忆、要不要挂媒体工具、挂哪一类工具」，不决定回复内容 ——
 * 所以解析失败的代价很低，直接落到 {@code chat} 继续走，绝不因为一个 JSON 没解析出来就报错。
 * 但 Action 类一旦误判成 chat，用户会得到一句口头答应、库里什么都没有，所以提示词里把
 * 「他说要提醒」写成了强特征。
 */
@Component
public class IntentNode implements GraphNode {

    private static final Logger log = LoggerFactory.getLogger(IntentNode.class);
    private static final List<String> KNOWN = List.of("chat", "emotion", "ask_media", "ask_memory",
            "smalltalk", StateKeys.INTENT_REMIND_SET, StateKeys.INTENT_REMIND_QUERY,
            StateKeys.INTENT_REMIND_CANCEL);

    private final LlmClient llm;
    private final PromptCatalog prompts;
    private final RuntimeSettings defaults;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IntentNode(LlmClient llm, PromptCatalog prompts, RuntimeSettings defaults) {
        this.llm = llm;
        this.prompts = prompts;
        this.defaults = defaults;
    }

    @Override
    public String id() {
        return "intent";
    }

    @Override
    public String name() {
        return "intent";
    }

    @Override
    public String label() {
        return "意图识别";
    }

    @Override
    public String type() {
        return "router";
    }

    @Override
    public String role() {
        return "classifier";
    }

    @Override
    public String description() {
        return "归类本轮意图，解析失败回落 chat";
    }

    @Override
    public List<String> inputKeys() {
        return List.of(StateKeys.REWRITTEN);
    }

    @Override
    public String outputKey() {
        return StateKeys.INTENT;
    }

    @Override
    public Flux<String> stream(OverAllState state) {
        EffectiveSettings settings = settingsOf(state, defaults);
        String question = state.value(StateKeys.REWRITTEN, "");
        if (question == null || question.isBlank() || !llm.configured(settings)) {
            return Flux.just("chat");
        }
        String system;
        try {
            system = prompts.render(PromptKeys.CHAT_INTENT_SYSTEM, Map.of("question", question));
        } catch (RuntimeException ex) {
            return Flux.just("chat");
        }
        return llm.complete(LlmClient.messages(system, null, question), 0.0, settings)
                .map(this::parse)
                .flux();
    }

    /** 模型可能带 ```json 包裹或多说一句，这里只认第一个 { 到最后一个 }。 */
    private String parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return "chat";
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return "chat";
        }
        try {
            JsonNode node = objectMapper.readTree(raw.substring(start, end + 1));
            String intent = node.path("intent").asText("");
            return KNOWN.contains(intent) ? intent : "chat";
        } catch (Exception ex) {
            log.debug("[intent] 解析失败，回落 chat: {}", raw);
            return "chat";
        }
    }
}
