package com.seeky.airuntime.skill;

/**
 * 技能命名空间：目录即前缀，节点只加载自己前缀下的技能。
 *
 * <p>与 ai-runtime 的 {@code SkillKeys} 同一套做法——{@code chat.output.send_photo} 只能被输出节点
 * 加载，别的节点既看不到也召不回，避免技能串台。
 */
public final class SkillKeys {

    private SkillKeys() {
    }

    /** 输出节点（最终回复）可用的技能前缀。 */
    public static final String CHAT_OUTPUT = "chat.output.";

    /**
     * 待办提醒技能：{@code skills/chat/output/reminder/SKILL.md}。
     *
     * <p>它的正文同时被两个节点读：工具节点拿它决定"什么时候必须动手设提醒"，
     * 输出节点拿它决定"答应了怎么说话"。所以这个能力只有<em>一份</em>规则来源，
     * 不会出现"工具说要调、嘴上说不用"这种自相矛盾。
     */
    public static final String REMINDER = CHAT_OUTPUT + "reminder";

    /** 每个技能都必须落在这个前缀下，裸名（不带点）启动直接报错。 */
    public static final java.util.List<String> SCOPES = java.util.List.of(CHAT_OUTPUT);
}
