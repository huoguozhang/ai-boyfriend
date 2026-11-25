package com.seeky.airuntime.prompt;

/**
 * 提示词键：文件即键，命名照 Nacos dataId 的习惯用 {@code .} 分层。
 *
 * <p>{@code persona.chat.system} 对应 {@code classpath:prompts/persona.chat.system.md}。
 * 键清单是代码里的唯一真相：{@link PromptCatalog} 启动时按这张表校验，缺任一个直接启动失败——
 * 提示词是强依赖，宁可起不来也不要带着半套 prompt 上线（表现会是"她偶尔像失忆"，极难排查）。
 */
public final class PromptKeys {

    private PromptKeys() {
    }

    /** 角色主提示词：身份、说话规范、范例对比（对应输出节点 system）。 */
    public static final String PERSONA_CHAT_SYSTEM = "persona.chat.system";

    /** 意图识别：把用户这句话归到闲聊 / 情绪 / 索要媒体 / 询问记忆 等。 */
    public static final String CHAT_INTENT_SYSTEM = "chat.intent.system";

    /** 问题改写：结合历史把"那它呢"补成自包含的一句话。 */
    public static final String CHAT_REWRITE_SYSTEM = "chat.rewrite.system";

    /**
     * 技能清单段落：拼在改写提示词末尾，把「名字 + 描述 + 优先级」交给模型挑。
     *
     * <p>不是必需的：一条技能都没配时这段为空，改写照常工作（只是不召回任何技能）。
     */
    public static final String CHAT_SKILL_CATALOG = "chat.skill.catalog";

    /** 工具决策：这一轮要不要发照片/视频、要不要记一件事、要不要想起来一件事。 */
    public static final String CHAT_TOOL_SYSTEM = "chat.tool.system";

    /** 记忆抽取：从一轮对话里抽出可长期保存的事实。 */
    public static final String MEMORY_EXTRACT_SYSTEM = "memory.extract.system";

    /** 记忆摘要：把多轮对话压成几句情景摘要。 */
    public static final String MEMORY_SUMMARY_SYSTEM = "memory.summary.system";

    /** 启动时必须存在的提示词（其余可选，缺了只是少个功能）。 */
    public static final java.util.Set<String> REQUIRED = java.util.Set.of(
            PERSONA_CHAT_SYSTEM,
            CHAT_INTENT_SYSTEM,
            CHAT_REWRITE_SYSTEM,
            MEMORY_EXTRACT_SYSTEM,
            MEMORY_SUMMARY_SYSTEM
    );
}
