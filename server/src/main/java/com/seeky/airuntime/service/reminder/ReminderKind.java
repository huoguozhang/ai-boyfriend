package com.seeky.airuntime.service.reminder;

/**
 * 提醒类型。需求里说的"一个类型"就是它。
 *
 * <p>做成枚举而不是随便存字符串：<b>类型是给程序用的，不是给人看的</b> ——
 * 以后要做"吃药提醒连响三遍""纪念日提前一天提醒"，全靠这个字段分流。
 * 模型可能给中文（"吃药"）也可能给英文（"medication"），{@link #of} 两种都认，
 * 认不出的一律落到 {@link #OTHER}，绝不因为一个类型词没对上就把整条提醒丢掉。
 */
public enum ReminderKind {

    MEDICATION("medication", "吃药"),
    WATER("water", "喝水"),
    SLEEP("sleep", "睡觉"),
    WAKE("wake", "起床"),
    APPOINTMENT("appointment", "约会/面试"),
    TODO("todo", "待办"),
    ANNIVERSARY("anniversary", "纪念日"),
    OTHER("other", "提醒");

    private final String code;
    private final String label;

    ReminderKind(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /** 存库用的稳定标识（英文，不随文案变）。 */
    public String code() {
        return code;
    }

    /** 给人看的中文名。 */
    public String label() {
        return label;
    }

    /** 宽容解析：中文名 / 英文码 / 都认不出 → OTHER。 */
    public static ReminderKind of(String raw) {
        if (raw == null || raw.isBlank()) {
            return OTHER;
        }
        String text = raw.strip().toLowerCase();
        for (ReminderKind kind : values()) {
            if (kind.code.equals(text) || kind.label.equals(text) || kind.name().equalsIgnoreCase(text)) {
                return kind;
            }
        }
        // 模型偶尔会给"吃药提醒""todo_reminder"这类带修饰的说法，按包含关系再兜一次
        for (ReminderKind kind : values()) {
            if (text.contains(kind.code) || text.contains(kind.label)) {
                return kind;
            }
        }
        return OTHER;
    }
}
