package com.seeky.airuntime.service.chat.nodes.common;

/**
 * 图状态键：节点之间靠这些字符串交接数据（与 ai-runtime 的 {@code StateKeys} 同义）。
 *
 * <p>为什么用常量而不是枚举：{@code OverAllState} 的 key 就是字符串，ai-runtime 那边也是常量类，
 * 保持同样的形态，从两边代码对照着看不用转换。
 */
public final class StateKeys {

    private StateKeys() {
    }

    /** 用户原始输入。 */
    public static final String QUERY = "query";
    /** 改写后的自包含问句（改写失败时等于原文）。 */
    public static final String REWRITTEN = "rewrittenQuery";
    /** 意图：chat / emotion / ask_media / ask_memory / smalltalk / remind_*。 */
    public static final String INTENT = "intent";

    /**
     * 「问答」与「动作」的分界线：下面三个是动作（要动手落一条真实的提醒），
     * 其余的意图都只影响这一轮读什么、怎么说话，不会写任何业务数据。
     */
    public static final String INTENT_REMIND_SET = "remind_set";
    public static final String INTENT_REMIND_QUERY = "remind_query";
    public static final String INTENT_REMIND_CANCEL = "remind_cancel";
    /** 召回的记忆文本块（已按字符上限截断）。 */
    public static final String MEMORY = "memoryBlock";
    /** 本轮要召回的场景技能名（改写模型按技能清单判定，输出节点按名单注入正文）。 */
    public static final String SKILLS = "skills";
    /** 工具节点这一轮做了什么（发给输出节点，避免她再说一遍"我发给你了"）。 */
    public static final String TOOL_RESULT = "toolResult";
    /** 工具节点推给前端的指令帧（管道内私有协议的 key，不是给人看的正文）。 */
    public static final String TOOL_FRAMES = "toolFrames";
    /** 角色（画像）文本块。 */
    public static final String PERSONA = "personaBlock";
    /** 本轮要不要发媒体：image / video / none。 */
    public static final String MEDIA_WISH = "mediaWish";
    /** 最终回复文本（输出节点的产出，同时也是流式 chunk 的 key）。 */
    public static final String TEXT = "text";

    /** 以下是 Service 放进初始状态的上下文，节点只读。 */
    public static final String USER_ID = "userId";
    /**
     * 用户希望被怎么称呼（注册时填的昵称，没填就是用户名）。
     *
     * <p>和记忆里的"他叫什么"不是一回事：记忆是模型从对话里听来的、可能记错也可能过期；
     * 这一项是用户在注册表单里明确写下的称呼，属于身份而不是回忆，所以按请求直接带进提示词。
     */
    public static final String USER_NAME = "userName";
    public static final String SESSION_ID = "sessionId";
    public static final String HISTORY = "history";

    /**
     * 本次请求的生效配置（.env 默认 + 客户端随请求带来的覆盖）。
     *
     * <p>配置按请求走，不存服务端：同一个进程里两个客户端可以各用各的模型与 Key。
     * 所以每个节点都必须从这里取配置，而不是回头读全局单例。
     */
    public static final String SETTINGS = "settings";
    public static final String DEGRADED = "degraded";
}
