package com.seeky.airuntime.service.chat.nodes;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.streaming.GraphFlux;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seeky.airuntime.config.EffectiveSettings;
import com.seeky.airuntime.config.RuntimeSettings;
import com.seeky.airuntime.prompt.PromptCatalog;
import com.seeky.airuntime.prompt.PromptKeys;
import com.seeky.airuntime.service.chat.LlmClient;
import com.seeky.airuntime.service.chat.nodes.common.GraphChunks;
import com.seeky.airuntime.service.chat.nodes.common.GraphNode;
import com.seeky.airuntime.service.chat.nodes.common.HistoryText;
import com.seeky.airuntime.service.chat.nodes.common.StateKeys;
import com.seeky.airuntime.service.chat.tools.SoulmateTools;
import com.seeky.airuntime.store.ChatStore;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 工具节点：先问她"要不要动手"，动手的结果直接推成帧给前端。
 *
 * <p>为什么单独一个节点、放在输出之前：发照片是**发送动作**，不该由"说话"的节点顺手做掉。
 * 顺序上必须在她开口之前把东西发出去 —— 不然她会先说"给你看"，图后到，或者干脆说"我发过了"
 * 然后什么也没有。
 *
 * <p>帧怎么出：本节点的产出是给前端的**指令**而不是给人看的字，所以走一套管道内的私有协议——
 * 每一块都是一行 {@code {"event":..,"data":..}}，Service 原样翻成 SSE 帧（media / notice 等），
 * 不掺进正文。这样做的好处是不用为了"发图"去改图的执行模型；代价是协议是私有的，
 * 所以它只在 node id 为 tool 的块上解析，正文块永远走 text-delta。
 */
@Component
public class ToolNode implements GraphNode {

    private static final Logger log = LoggerFactory.getLogger(ToolNode.class);
    /** 一轮最多调几次工具：防止模型连环调用把一轮对话拖成半分钟。 */
    private static final int MAX_CALLS_PER_TURN = 2;
    private static final int HISTORY_WINDOW = 8;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 时间线索（只看"要不要强制重试"，不参与解析；解析在 ReminderTime）。 */
    private static final java.util.regex.Pattern TIMED_HINT = java.util.regex.Pattern.compile(
            "(\\d|[一二两三四五六七八九十半])\\s*(秒|分钟|分|小时|个?钟头|天|日|周|星期|个?月|号|点)"
                    + "|\\d{1,2}\\s*[:：]\\s*\\d{2}"
                    + "|明天|明早|明晚|今天|今晚|后天|大后天|下周|下个?月|周末");

    private final LlmClient llm;
    private final PromptCatalog prompts;
    /** .env 默认值；本次请求的真实配置从状态里取。 */
    private final RuntimeSettings defaults;
    private final SoulmateTools tools;
    private final ChatStore store;

    public ToolNode(LlmClient llm, PromptCatalog prompts, RuntimeSettings defaults,
            SoulmateTools tools, ChatStore store) {
        this.llm = llm;
        this.prompts = prompts;
        this.defaults = defaults;
        this.tools = tools;
        this.store = store;
    }

    /**
     * 按意图挑工具集：问答就给"查/记"这类的，动作就给对应的那一把。
     *
     * <p>为什么不在"全量工具 + 模型自己选"上打住：工具越多，模型越容易在闲聊里手滑调错；
     * 而且设提醒这种动作，只要它这一轮没调，用户得到的"我记住了"就是一句空话。
     * 所以意图一旦判明是动作，这一轮只挂得下那一个工具。
     *
     * @param intent 意图节点给出的分类
     */
    private Plan planFor(String intent) {
        return switch (intent == null ? "" : intent) {
            case StateKeys.INTENT_REMIND_SET -> new Plan(SoulmateTools.REMINDER_TOOLS, SoulmateTools.SET_REMINDER);
            case StateKeys.INTENT_REMIND_QUERY -> new Plan(SoulmateTools.REMINDER_TOOLS, SoulmateTools.LIST_REMINDERS);
            case StateKeys.INTENT_REMIND_CANCEL -> new Plan(SoulmateTools.REMINDER_TOOLS, SoulmateTools.CANCEL_REMINDER);
            case "ask_media" -> new Plan(SoulmateTools.MEDIA_TOOLS, null);
            case "ask_memory" -> new Plan(SoulmateTools.MEMORY_TOOLS, null);
            // 闲聊也可能顺手让他想起一件事，保留全量给模型自己判断
            default -> new Plan(null, null);
        };
    }

    /**
     * 这一轮的工具集。
     *
     * @param names  只挂这几个（null = 全挂）
     * @param forced 必须调的那个（null = 让模型自己决定）
     */
    private record Plan(java.util.List<String> names, String forced) {
    }

    @Override
    public String id() {
        return "tool";
    }

    @Override
    public String name() {
        return "tool";
    }

    @Override
    public String label() {
        return "工具";
    }

    @Override
    public String type() {
        return "tool";
    }

    @Override
    public String role() {
        return "executor";
    }

    @Override
    public String description() {
        return "按意图挂工具：发照片/视频、记一件事、想起来一件事、设/查/取消待办提醒";
    }

    @Override
    public List<String> inputKeys() {
        return List.of(StateKeys.REWRITTEN, StateKeys.HISTORY, StateKeys.USER_ID,
                StateKeys.SESSION_ID, StateKeys.INTENT);
    }

    @Override
    public String outputKey() {
        return StateKeys.TOOL_RESULT;
    }

    /** 本节点要往管道里推帧，必须自己包成 ChatResponse（见 {@link GraphChunks}）。 */
    @Override
    public boolean streaming() {
        return true;
    }

    @Override
    public AsyncNodeAction action() {
        return AsyncNodeAction.node_async(state -> {
            Map<String, Object> result = new LinkedHashMap<>();
            List<Map<String, Object>> frames = new ArrayList<>();
            String toolResult = execute(state, frames);
            // 给输出节点看的文字走 TOOL_RESULT，推给前端的指令帧走 TOOL_FRAMES，两个 key 不能混
            result.put(outputKey(), toolResult);
            // 什么都没调就**不要**往状态里塞 GraphFlux：框架遇到空流会直接判本轮失败
            // （"Empty flux detected for key 'toolFrames'"），把一次正常闲聊毁掉
            if (!frames.isEmpty()) {
                Flux<ChatResponse> flux = Flux.fromIterable(frames)
                        .map(frame -> GraphChunks.chunk(frameJson(frame)));
                result.put(StateKeys.TOOL_FRAMES, GraphFlux.of(id(), StateKeys.TOOL_FRAMES, flux));
            }
            return result;
        });
    }

    @Override
    public Flux<String> stream(OverAllState state) {
        return Flux.empty();
    }

    /**
     * 决策 + 执行，帧塞进 {@code frames}，返回一段"这一轮发生了什么"的文字交给输出节点。
     */
    private String execute(OverAllState state, List<Map<String, Object>> frames) {
        EffectiveSettings settings = settingsOf(state, defaults);
        if (tools.empty() || !llm.configured(settings)) {
            return "";
        }
        String query = state.value(StateKeys.REWRITTEN, "");
        String sessionId = state.value(StateKeys.SESSION_ID, "");
        String userId = state.value(StateKeys.USER_ID, "");
        String intent = state.value(StateKeys.INTENT, "");
        Plan plan = planFor(intent);

        List<Map<String, Object>> definitions = tools.definitions(plan.names());
        if (definitions.isEmpty()) {
            return "";
        }
        String system = toolSystem(settings);
        LlmClient.ToolTurn turn = llm.withTools(
                LlmClient.messages(system, historyOnly(state), query),
                definitions, plan.forced(), 0.2, settings).block();

        // 兜底重试：意图说"他要我设提醒"、句子里也确实带了时间，模型却没动手 ——
        // 这一轮要是就这么滑过去，她只能嘴上答应，用户以为记上了，实际库里什么都没有。
        // 只在"有明确时间线索"时才强制，纯「提醒我吃药」这种缺时间的，让她正常反问，不能替她编一个时刻。
        if ((turn == null || !turn.hasCalls()) && plan.forced() != null
                && looksTimed(query)) {
            log.info("[tool] 意图={} 但模型没调工具，强制再问一次 {}", intent, plan.forced());
            turn = llm.withTools(LlmClient.messages(system, historyOnly(state), query),
                    definitions, plan.forced(), 0.2, settings).block();
        }

        if (turn == null || !turn.hasCalls()) {
            return "";
        }

        StringBuilder result = new StringBuilder();
        int executed = 0;
        for (LlmClient.ToolCall call : turn.calls()) {
            if (executed >= MAX_CALLS_PER_TURN) {
                log.info("[tool] 本轮已调 {} 次，忽略多余的 {}", executed, call.name());
                break;
            }
            executed += 1;
            frames.add(frame("tool-call-start", Map.of("name", call.name())));
            SoulmateTools.Execution execution = tools.execute(call.name(),
                    arguments(call.arguments()), userId, sessionId);

            if (execution.media()) {
                frames.add(frame(execution.jobId() == null ? "media" : "media-pending", mediaData(execution)));
                if (execution.jobId() == null) {
                    // 真发出去的东西要落库：刷新页面时这条照片还在
                    store.append(sessionId, userId, "assistant", execution.caption(),
                            mediaJson(execution));
                }
            }
            if (execution.notice() != null && !execution.notice().isBlank()) {
                frames.add(frame("notice", Map.of("message", execution.notice(), "level", "info")));
            }
            if (execution.memoryChanged()) {
                frames.add(frame("memory-updated", Map.of()));
            }
            frames.add(frame("tool-result", Map.of("name", call.name())));
            // 给输出节点看的文字<b>不带工具名</b>：ark-code-latest 是 Agent Plan 模型，
            // 一旦在上下文里看到 "set_reminder" 这类工具名，会自己补一行
            // "[Calling tool: set_reminder ...]" 当成回复发出去 —— 用户会看到一段技术垃圾。
            // 它只需要知道"发生了什么"，不需要知道"调的是哪个工具"。
            if (!execution.toolResult().isBlank()) {
                result.append("- ").append(execution.toolResult()).append('\n');
            }
            if (execution.media()) {
                result.append("- 已经发出去一张图片/视频，配文「")
                        .append(execution.caption()).append("」\n");
            }
        }
        return result.toString().strip();
    }

    private Map<String, Object> mediaData(SoulmateTools.Execution execution) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messageId", java.util.UUID.randomUUID().toString());
        data.put("kind", execution.kind());
        data.put("caption", execution.caption());
        if (execution.jobId() != null) {
            // 视频还没出片：只给任务号，别把几十 KB 的封面塞进帧里（前端拿到 jobId 会去轮询）
            data.put("jobId", execution.jobId());
            data.put("posterOnly", true);
        } else {
            data.put("url", execution.url() == null ? "" : execution.url());
        }
        return data;
    }

    private String mediaJson(SoulmateTools.Execution execution) {
        Map<String, Object> media = new LinkedHashMap<>();
        media.put("kind", execution.kind());
        media.put("url", execution.url());
        media.put("caption", execution.caption());
        try {
            return JSON.writeValueAsString(media);
        } catch (Exception ex) {
            return null;
        }
    }

    private Map<String, Object> frame(String event, Map<String, Object> data) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("event", event);
        frame.put("data", data);
        return frame;
    }

    private String frameJson(Map<String, Object> frame) {
        try {
            return JSON.writeValueAsString(frame);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private JsonNode arguments(String raw) {
        try {
            return raw == null || raw.isBlank() ? JSON.createObjectNode() : JSON.readTree(raw);
        } catch (Exception ex) {
            log.warn("[tool] 参数不是合法 JSON，按空参数处理: {}", raw);
            return JSON.createObjectNode();
        }
    }

    /** 工具决策只需要"最近说过什么"，不需要角色卡与记忆块（那是说话用的）。 */
    private List<Map<String, String>> historyOnly(OverAllState state) {
        List<Map<String, String>> history = state.value(StateKeys.HISTORY, List.of());
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return history.subList(Math.max(0, history.size() - HISTORY_WINDOW), history.size());
    }

    private String toolSystem(EffectiveSettings settings) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("name", settings.name());
        vars.put("age", settings.age());
        vars.put("city", settings.city());
        vars.put("job", settings.job());
        vars.put("nowText", nowText());
        try {
            return prompts.render(PromptKeys.CHAT_TOOL_SYSTEM, vars);
        } catch (RuntimeException ex) {
            // 提示词缺了不该影响对话：没有工具决策的约束，最坏也就是这一轮不调工具
            log.warn("[tool] 提示词渲染失败，本轮不做工具决策: {}", ex.getMessage());
            return "你是" + settings.name() + "。";
        }
    }

    /**
     * 这句话里有没有明确的时间线索。
     *
     * <p>只用来决定"要不要强制重试一次工具调用"，宁可漏判（漏了就走模型自己的判断），
     * 不能误判成"有时间"然后替用户编一个时刻出来。
     */
    static boolean looksTimed(String text) {
        return text != null && TIMED_HINT.matcher(text).find();
    }

    /**
     * 给工具决策用的"现在"。
     *
     * <p>必须精确到分钟并带年份：这里要算"三天后""明天八点"，只给"11 月 25 日下午"
     * 模型根本没法算，只能瞎猜一个日期 —— 而算错的提醒比不提醒更伤人。
     */
    private String nowText() {
        LocalDateTime now = LocalDateTime.now();
        String[] week = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return now.getYear() + " 年 " + now.getMonthValue() + " 月 " + now.getDayOfMonth() + " 日 "
                + week[now.getDayOfWeek().getValue() - 1] + " "
                + String.format("%02d:%02d", now.getHour(), now.getMinute());
    }
}
