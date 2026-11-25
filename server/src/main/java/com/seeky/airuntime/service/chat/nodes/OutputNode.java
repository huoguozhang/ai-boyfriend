package com.seeky.airuntime.service.chat.nodes;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.streaming.GraphFlux;
import com.seeky.airuntime.config.EffectiveSettings;
import com.seeky.airuntime.config.RuntimeSettings;
import com.seeky.airuntime.prompt.PromptCatalog;
import com.seeky.airuntime.prompt.PromptKeys;
import com.seeky.airuntime.service.chat.LlmClient;
import com.seeky.airuntime.service.chat.nodes.common.GraphChunks;
import com.seeky.airuntime.service.chat.nodes.common.GraphNode;
import com.seeky.airuntime.service.chat.nodes.common.StateKeys;
import com.seeky.airuntime.skill.SkillCatalog;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 输出节点：组装 system 提示词，流式生成回复。全图唯一把字推给用户的节点。
 *
 * <p>system 由四段拼成：角色卡（她是谁）+ 记忆（她知道什么）+ 能力（她能做什么）+
 * 技能（这轮要守什么规则）。四段各有来源，谁都不许越界写别人的内容 ——
 * 这也是为什么角色卡里只有性格和说话方式，没有"记得他不吃香菜"。
 *
 * <p>流式失败（模型挂了 / 超时）：返回空流，由 Service 转成 error 帧，
 * 前端会显示"她说不出话来了"，而不是把半句话留在屏幕上。
 */
@Component
public class OutputNode implements GraphNode {

    private static final Logger log = LoggerFactory.getLogger(OutputNode.class);

    /** 本轮没有任何技能被召回时的交代：留空会让模型自己脑补规则。 */
    private static final String NO_SKILL = "（本轮没有额外的场景规则，按上面的说话方式自然回复）";

    private final LlmClient llm;
    private final PromptCatalog prompts;
    /** .env 默认值；本次请求的真实配置从状态里取（客户端传了就用客户端的）。 */
    private final RuntimeSettings defaults;
    private final SkillCatalog skills;

    public OutputNode(LlmClient llm, PromptCatalog prompts,
            RuntimeSettings defaults, SkillCatalog skills) {
        this.llm = llm;
        this.prompts = prompts;
        this.defaults = defaults;
        this.skills = skills;
    }

    @Override
    public String id() {
        return "output";
    }

    @Override
    public String name() {
        return "output";
    }

    @Override
    public String label() {
        return "输出";
    }

    @Override
    public String type() {
        return "output";
    }

    @Override
    public String role() {
        return "generator";
    }

    @Override
    public String description() {
        return "拼装 system 并流式输出";
    }

    @Override
    public List<String> inputKeys() {
        return List.of(StateKeys.REWRITTEN, StateKeys.MEMORY, StateKeys.PERSONA,
                StateKeys.INTENT, StateKeys.SKILLS, StateKeys.USER_NAME);
    }

    @Override
    public String outputKey() {
        return StateKeys.TEXT;
    }

    /** 全图唯一流式节点：逐 token 推给用户。 */
    @Override
    public boolean streaming() {
        return true;
    }

    /**
     * 覆盖默认动作，把 token 流包成 {@code ChatResponse} 再交给 GraphFlux。
     *
     * <p>为什么不能直接包 {@code Flux<String>}：框架把 String 元素塞进 StreamingOutput 时
     * 取不到正文，实测 {@code chunk()} 恒为空 —— 用户侧表现就是"一个字都没有"。
     * {@code ChatResponse} 是它原生认识的类型，{@code chunk()} 会正确取出 delta 文本。
     */
    @Override
    public AsyncNodeAction action() {
        return AsyncNodeAction.node_async(state -> {
            Map<String, Object> result = new LinkedHashMap<>();
            Flux<ChatResponse> flux = stream(state).map(GraphChunks::chunk);
            result.put(outputKey(), GraphFlux.of(id(), outputKey(), flux));
            return result;
        });
    }

    @Override
    public Flux<String> stream(OverAllState state) {
        EffectiveSettings settings = settingsOf(state, defaults);
        String query = state.value(StateKeys.REWRITTEN, "");
        String memory = state.value(StateKeys.MEMORY, "");
        List<Map<String, String>> history = state.value(StateKeys.HISTORY, List.of());

        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("name", settings.name());
        vars.put("age", settings.age());
        vars.put("city", settings.city());
        vars.put("job", settings.job());
        vars.put("personality", settings.personality());
        vars.put("speakingStyle", settings.speakingStyle());
        vars.put("backstory", settings.backstory());
        vars.put("petLine", settings.petName() == null || settings.petName().isBlank()
                ? "" : "你养了只猫叫" + settings.petName() + "，你经常提到它。");
        // 她管他叫什么：注册时填的昵称（没填就是用户名）。空串时不要求她刻意喊称呼。
        String callName = state.value(StateKeys.USER_NAME, "");
        vars.put("userLine", callName == null || callName.isBlank()
                ? "" : "他希望你叫他「" + callName + "」，平时聊天自然用这个称呼，但不要每句都喊。");
        vars.put("nowText", nowText());
        vars.put("lastTalkLine", "");
        vars.put("memoryBlock", memory == null || memory.isBlank()
                ? "（这次没有想起什么具体的事）" : memory);
        vars.put("capabilityBlock", capabilityText(settings));
        vars.put("skillBlock", skillText(state));

        String system;
        try {
            system = prompts.render(PromptKeys.PERSONA_CHAT_SYSTEM, vars) + toolBlock(state);
        } catch (RuntimeException ex) {
            log.error("[output] 提示词渲染失败: {}", ex.getMessage());
            return Flux.empty();
        }
        return llm.stream(LlmClient.messages(system, history, query), settings);
    }

    /**
     * 这一轮工具已经做过的事，补在 system 末尾。
     *
     * <p>为什么要交代：照片是工具发出去的，模型自己并不"知道"发了什么。不告诉它，
     * 它要么再说一遍"我发给你了"，要么以为没发成又发一次。
     *
     * <p>第二段是"失败要认"：设提醒时时间听不懂、已经过去了，工具会回一句失败原因。
     * 不把这条规矩写死，她照样会顺着用户的话说"好的我记住了" —— 那就是彻底的空头承诺。
     */
    private String toolBlock(OverAllState state) {
        String done = state.value(StateKeys.TOOL_RESULT, "");
        if (done == null || done.isBlank()) {
            return "";
        }
        return "\n\n【这一轮已经做完的事（当背景知道即可，你不用再调用任何工具、也不用输出任何工具指令）】\n" + done
                + "\n照上面的事实接话就好：事情已经办完了，你只需要说一句人话回应。"
                + "\n如果上面出现「已经过去了」「没找到」「没落库」「看不懂」这类结果，"
                + "说明这件事没做成：如实说没记上，并问清关键信息，"
                + "绝对不许说成「我记住了」「已经设好了」。";
    }

    /** 她能做什么：只列真正配好的能力，没配的绝不写进来（否则她会答应"等下拍给你"然后发不出来）。 */
    private String capabilityText(EffectiveSettings settings) {
        StringBuilder sb = new StringBuilder();
        if (settings.image().configured()) {
            sb.append("- 你可以发照片：需要时调用 send_photo 工具，不要在文字里假装发了\n");
        }
        if (settings.video().configured()) {
            sb.append("- 你可以发短视频：需要时调用 send_video 工具\n");
        }
        if (settings.tts().configured() && !settings.tts().mock()) {
            sb.append("- 你说话可以被转成语音\n");
        }
        return sb.isEmpty() ? "你现在只能打字聊天，不能发照片或视频。" : sb.toString().trim();
    }

    /** 本轮技能块：召回名单 → SKILL.md 正文；没召回就明确说"没有额外规则"。 */
    private String skillText(OverAllState state) {
        List<String> names = state.value(StateKeys.SKILLS, List.of());
        if (names == null || names.isEmpty()) {
            return NO_SKILL;
        }
        StringBuilder sb = new StringBuilder();
        for (String name : names) {
            // 名单来自模型，进来时已经过 catalog.contains 净化；这里再挡一次，防止状态被别处塞脏
            if (!skills.contains(name)) {
                continue;
            }
            sb.append("\n\n## ").append(name)
                    .append("（优先级 ").append(skills.priority(name)).append("）\n")
                    .append(skills.content(name).strip());
        }
        return sb.isEmpty() ? NO_SKILL : sb.toString().strip();
    }

    private String nowText() {
        LocalDateTime now = LocalDateTime.now();
        String[] week = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        int hour = now.getHour();
        String period = hour < 6 ? "凌晨" : hour < 12 ? "上午" : hour < 14 ? "中午"
                : hour < 18 ? "下午" : hour < 22 ? "晚上" : "深夜";
        return "现在是 " + now.getMonthValue() + " 月 " + now.getDayOfMonth() + " 日 "
                + week[now.getDayOfWeek().getValue() - 1] + period + "。";
    }
}
