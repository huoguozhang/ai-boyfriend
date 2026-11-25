package com.seeky.airuntime.service.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 时间解析 —— 这是提醒功能最不能错的一块：算错一天的提醒比不提醒更伤人。
 *
 * <p>全部纯单元测试，固定"现在"到 2026-11-25 19:30（周三），时区 Asia/Shanghai。
 */
class ReminderTimeTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    // 2026-11-25 19:30 周三（Asia/Shanghai）
    private static final long NOW = java.time.LocalDateTime.of(2026, 11, 25, 19, 30)
            .atZone(ZONE).toInstant().toEpochMilli();

    private static long at(String remindAt, String remindIn) {
        return ReminderTime.resolve(remindAt, remindIn, NOW, ZONE).atMillis();
    }

    private static String clock(String remindAt, String remindIn) {
        return java.time.LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(at(remindAt, remindIn)), ZONE)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    // ------------------------------------------------------------------ 相对

    @Test
    @DisplayName("三天后 → 三天后的同一个时刻")
    void relativeDays() {
        assertThat(clock(null, "3d")).isEqualTo("2026-11-28 19:30");
    }

    @Test
    @DisplayName("2h30m → 加两个半小时")
    void relativeHoursAndMinutes() {
        assertThat(clock(null, "2h30m")).isEqualTo("2026-11-25 22:00");
    }

    @Test
    @DisplayName("90m / 30分钟 是同一回事")
    void relativeMinutes() {
        assertThat(clock(null, "90m")).isEqualTo("2026-11-25 21:00");
        assertThat(clock(null, "30分钟")).isEqualTo("2026-11-25 20:00");
    }

    @Test
    @DisplayName("1w / 2mo 按 7 天 / 30 天算")
    void relativeWeeksAndMonths() {
        assertThat(clock(null, "1w")).isEqualTo("2026-12-02 19:30");
        assertThat(clock(null, "2mo")).isEqualTo("2027-01-24 19:30");
    }

    @Test
    @DisplayName("相对说法优先于绝对说法")
    void relativeWinsOverAbsolute() {
        assertThat(clock("2026-11-30 08:00", "1d")).isEqualTo("2026-11-26 19:30");
    }

    @Test
    @DisplayName("一个单位都不认识 → 报错而不是瞎猜")
    void relativeRejectsGarbage() {
        assertThatThrownBy(() -> at(null, "晚点"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("看不懂");
    }

    // ------------------------------------------------------------------ 绝对

    @Test
    @DisplayName("标准 ISO 格式直接命中")
    void absoluteIso() {
        assertThat(clock("2026-11-28 20:00", null)).isEqualTo("2026-11-28 20:00");
    }

    @Test
    @DisplayName("中文写法：2026年11月28日 20点 → 20:00")
    void absoluteChineseFull() {
        assertThat(clock("2026年11月28日 20点", null)).isEqualTo("2026-11-28 20:00");
    }

    @Test
    @DisplayName("晚上八点 → 当天 20:00")
    void eveningClock() {
        assertThat(clock("晚上八点", null)).isEqualTo("2026-11-25 20:00");
    }

    @Test
    @DisplayName("下午三点 → 15:00")
    void afternoonClock() {
        assertThat(clock("下午三点", null)).isEqualTo("2026-11-26 15:00");
    }

    @Test
    @DisplayName("明天八点 → 明天早上八点")
    void tomorrowMorning() {
        assertThat(clock("明天八点", null)).isEqualTo("2026-11-26 08:00");
    }

    @Test
    @DisplayName("后天晚上八点 → 后天 20:00")
    void dayAfterTomorrow() {
        assertThat(clock("后天晚上八点", null)).isEqualTo("2026-11-27 20:00");
    }

    @Test
    @DisplayName("只有日期没时刻 → 默认早上九点")
    void dateOnlyDefaultsToNine() {
        assertThat(clock("2026-11-28", null)).isEqualTo("2026-11-28 09:00");
    }

    @Test
    @DisplayName("只报钟点且已过 → 顺延到明天（八点默认说的是下一次八点）")
    void barePastClockRollsToTomorrow() {
        assertThat(clock("19:00", null)).isEqualTo("2026-11-26 19:00");
    }

    @Test
    @DisplayName("已过去的明确日期 → 也拒绝")
    void pastDateIsRejected() {
        assertThatThrownBy(() -> at("2026-11-24 20:00", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已经过去");
    }

    @Test
    @DisplayName("两种时间都不给 → 报错")
    void noTimeAtAllIsRejected() {
        assertThatThrownBy(() -> at("", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有给出提醒时间");
    }

    @Test
    @DisplayName("五年开外 → 拒绝")
    void tooFarAheadIsRejected() {
        assertThatThrownBy(() -> at("2032-01-01 08:00", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("太远");
    }

    // ------------------------------------------------------------------ 复述

    @Test
    @DisplayName("复述文案：今天/明天/后天用相对词，更远报日期")
    void humanText() {
        assertThat(ReminderTime.human(at("20:30", null), NOW, ZONE)).contains("今天 20:30");
        assertThat(ReminderTime.human(at(null, "1d"), NOW, ZONE)).contains("明天 19:30");
        assertThat(ReminderTime.human(at("2026-12-01 08:00", null), NOW, ZONE)).contains("12 月 1 日 08:00");
    }
}
