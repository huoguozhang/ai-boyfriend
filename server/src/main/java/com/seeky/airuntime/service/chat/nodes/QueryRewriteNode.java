package com.seeky.airuntime.service.chat.nodes;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seeky.airuntime.config.EffectiveSettings;
import com.seeky.airuntime.config.RuntimeSettings;
import com.seeky.airuntime.prompt.PromptCatalog;
import com.seeky.airuntime.prompt.PromptKeys;
import com.seeky.airuntime.service.chat.LlmClient;
import com.seeky.airuntime.service.chat.nodes.common.GraphNode;
import com.seeky.airuntime.service.chat.nodes.common.HistoryText;
import com.seeky.airuntime.service.chat.nodes.common.StateKeys;
import com.seeky.airuntime.skill.SkillCatalog;
import com.seeky.airuntime.skill.SkillKeys;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 问题改写 + 技能召回：一次调用同时产出两样东西。
 *
 * <p><b>为什么要改写</b>：记忆检索是拿 query 去 mem0 做语义检索的，「那它呢」这种句子检索出来的
 * 东西完全随机，她会突然开始聊一件风马牛不相及的事。
 *
 * <p><b>为什么技能召回并在这里做</b>：照 ai-runtime 的做法，改写模型本来就要读一遍历史 + 本轮输入，
 * 顺手让它从技能清单里挑名字，比再单独发一次「你是技能调度器」的请求省一整次往返
 * （实测多一次低温调用是 300~800ms，陪伴对话里这一下很扎耳朵）。
 *
 * <p><b>失败姿势</b>：模型没配、超时、返回空 —— 一律**回退原句 + 不召回技能**，不阻断对话。
 * ai-runtime 那边改写失败会重试后终止，但那是医疗问答（答错的代价高）；
 * 这里宁可她稍微答得飘一点，也不能因为改写挂了就不说话。模型没按 JSON 格式回、只回了一句话时，
 * 那句话照样当改写结果用 —— 只有技能召回跟着失效，这比丢掉改写划算。
 */
@Component
public class QueryRewriteNode implements GraphNode {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteNode.class);
    /** 改写只看最近 8 句，再长既慢又容易被早先的话题带偏。 */
    private static final int HISTORY_WINDOW = 8;
    /** 改写是"给一句话"的活，温度低一点；顺带挑技能也不需要发散。 */
    private static final double TEMPERATURE = 0.2;
    /** 兜底空串：模型没给改写时不至于把 null 写进状态。 */
    private static final String EMPTY = "";
    /** Spring Boot 4 里 Jackson 2 的 ObjectMapper 不是容器 bean，自己持有一个即可。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final LlmClient llm;
    private final PromptCatalog prompts;
    private final SkillCatalog skills;
    private final RuntimeSettings defaults;

    public QueryRewriteNode(LlmClient llm, PromptCatalog prompts, SkillCatalog skills,
            RuntimeSettings defaults) {
        this.llm = llm;
        this.prompts = prompts;
        this.skills = skills;
        this.defaults = defaults;
    }

    @Override
    public String id() {
        return "queryRewrite";
    }

    @Override
    public String name() {
        return "query_rewrite";
    }

    @Override
    public String label() {
        return "问题改写";
    }

    @Override
    public String type() {
        return "agent";
    }

    @Override
    public String role() {
        return "preprocessor";
    }

    @Override
    public String description() {
        return "把省略句补成自包含问句并召回本轮技能，失败回退原句";
    }

    @Override
    public List<String> inputKeys() {
        return List.of(StateKeys.QUERY, StateKeys.HISTORY);
    }

    @Override
    public String outputKey() {
        return StateKeys.REWRITTEN;
    }

    /**
     * 覆盖默认动作：本节点写两个键（改写句 + 技能名单），而 {@code GraphNode} 默认只写一个。
     *
     * <p>不能把技能名单塞进第二个 {@code GraphFlux}：中间节点一旦写 GraphFlux，下游
     * {@code state.value(...)} 读到的就是包装对象，强转 String 直接 ClassCastException。
     */
    @Override
    public AsyncNodeAction action() {
        return AsyncNodeAction.node_async(state -> {
            Rewrite rewrite = rewrite(state);
            Map<String, Object> output = new LinkedHashMap<>();
            output.put(StateKeys.REWRITTEN, rewrite.query());
            output.put(StateKeys.SKILLS, rewrite.skills());
            return output;
        });
    }

    /** 接口要求的产出流：本节点不向用户吐字，这里只把改写结果交出去（给 {@code stream()} 场景）。 */
    @Override
    public Flux<String> stream(OverAllState state) {
        return Flux.just(rewrite(state).query());
    }

    /** 一轮改写的产物：自包含问句 + 本轮要遵守的技能名。 */
    private record Rewrite(String query, List<String> skills) {
    }

    private Rewrite rewrite(OverAllState state) {
        EffectiveSettings settings = settingsOf(state, defaults);
        String query = state.value(StateKeys.QUERY, EMPTY);
        if (query == null || query.isBlank() || !llm.configured(settings)) {
            return new Rewrite(query == null ? EMPTY : query, List.of());
        }
        String system;
        try {
            system = prompts.render(PromptKeys.CHAT_REWRITE_SYSTEM, Map.of(
                    "history", HistoryText.of(state, HISTORY_WINDOW),
                    "question", query,
                    "skillCatalog", skillCatalogSection()));
        } catch (RuntimeException ex) {
            log.warn("[rewrite] 提示词渲染失败，回退原句: {}", ex.getMessage());
            return new Rewrite(query, List.of());
        }
        String raw = llm.complete(LlmClient.messages(system, null, query), TEMPERATURE, settings)
                .block(BLOCK_TIMEOUT);
        Rewrite rewrite = parse(raw, query);
        if (!rewrite.skills().isEmpty()) {
            log.info("[rewrite] 本轮召回技能: {}", rewrite.skills());
        }
        return rewrite;
    }

    /**
     * 技能清单段落：只列输出节点前缀下的技能，按优先级排序（冲突时模型才知道谁先）。
     *
     * <p>一条技能都没有（或全都因能力未配置被跳过）时返回空串，改写提示词里那块就自然消失。
     */
    private String skillCatalogSection() {
        Map<String, String> descriptions = skills.descriptions(SkillKeys.CHAT_OUTPUT);
        if (descriptions.isEmpty()) {
            return EMPTY;
        }
        StringBuilder list = new StringBuilder();
        descriptions.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, String> entry) -> skills.priority(entry.getKey()))
                        .thenComparing(Map.Entry::getKey))
                .forEach(entry -> list.append("- ").append(entry.getKey())
                        .append("（优先级 ").append(skills.priority(entry.getKey())).append("）")
                        .append("：").append(entry.getValue()).append('\n'));
        String template = prompts.text(PromptKeys.CHAT_SKILL_CATALOG);
        String body = list.toString().stripTrailing();
        return template.isBlank() ? body : template.replace("{{skills}}", body);
    }

    /**
     * 解析模型的回复。
     *
     * <p>三道闸：容错取 JSON 子串（模型爱在外面套代码块或说明文字）→ 只认清单里真实存在的名字
     * （挡住幻觉）→ 任何一步失败都退回"原句 + 无技能"。
     */
    private Rewrite parse(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return new Rewrite(fallback, List.of());
        }
        String text = stripFence(raw).strip();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                JsonNode root = JSON.readTree(text.substring(start, end + 1));
                String query = root.path("query").asText(EMPTY).strip();
                List<String> chosen = new ArrayList<>();
                JsonNode names = root.path("skills");
                if (names.isArray()) {
                    for (JsonNode item : names) {
                        String name = item.asText(EMPTY).strip();
                        if (name.startsWith(SkillKeys.CHAT_OUTPUT) && skills.contains(name) && !chosen.contains(name)) {
                            chosen.add(name);
                        }
                    }
                }
                return new Rewrite(query.isEmpty() ? fallback : query, List.copyOf(chosen));
            } catch (Exception ex) {
                log.warn("[rewrite] 回复不是合法 JSON，按纯文本当改写结果: {}", shorten(text));
            }
        }
        // 没按格式来：整段第一行当改写结果，技能不召回 —— 保住改写比保住技能重要
        String plain = text.split("\n")[0].strip().replaceAll("^[\"“]|[\"”]$", "").strip();
        return new Rewrite(plain.isEmpty() ? fallback : plain, List.of());
    }

    private String stripFence(String raw) {
        String text = raw.strip();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        int lastFence = text.lastIndexOf("```");
        if (firstNewline < 0 || lastFence <= firstNewline) {
            return text;
        }
        return text.substring(firstNewline + 1, lastFence);
    }

    private String shorten(String text) {
        String one = text.replaceAll("\\s+", " ");
        return one.length() > 120 ? one.substring(0, 120) + "…" : one;
    }
}
