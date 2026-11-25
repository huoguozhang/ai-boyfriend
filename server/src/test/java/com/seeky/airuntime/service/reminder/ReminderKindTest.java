package com.seeky.airuntime.service.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 提醒类型的宽容解析：中文名、英文码都认，认不出落 other，绝不丢。 */
class ReminderKindTest {

    @Test
    @DisplayName("英文码直接命中")
    void codeMatches() {
        assertThat(ReminderKind.of("medication")).isEqualTo(ReminderKind.MEDICATION);
    }

    @Test
    @DisplayName("中文名命中")
    void chineseMatches() {
        assertThat(ReminderKind.of("吃药")).isEqualTo(ReminderKind.MEDICATION);
        assertThat(ReminderKind.of("喝水")).isEqualTo(ReminderKind.WATER);
    }

    @Test
    @DisplayName("大小写不敏感")
    void caseInsensitive() {
        assertThat(ReminderKind.of("MEDICATION")).isEqualTo(ReminderKind.MEDICATION);
    }

    @Test
    @DisplayName("带修饰的说法按包含关系兜底")
    void fuzzyMatches() {
        assertThat(ReminderKind.of("吃药提醒")).isEqualTo(ReminderKind.MEDICATION);
    }

    @Test
    @DisplayName("认不出落 other，不报错")
    void unknownFallsBack() {
        assertThat(ReminderKind.of("洗车")).isEqualTo(ReminderKind.OTHER);
        assertThat(ReminderKind.of("")).isEqualTo(ReminderKind.OTHER);
        assertThat(ReminderKind.of(null)).isEqualTo(ReminderKind.OTHER);
    }
}
