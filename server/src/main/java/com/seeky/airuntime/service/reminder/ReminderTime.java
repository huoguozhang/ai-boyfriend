package com.seeky.airuntime.service.reminder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把模型给的时间说法算成绝对时刻。
 *
 * <p><b>为什么不让模型直接给毫秒/时间戳</b>：它算术不稳（"三天后的这个点"经常差一天），
 * 而算错一天的提醒比不提醒更伤人。所以约定两种 Input：
 * <ul>
 *   <li>{@code remindIn}（相对）：{@code 3d / 2h30m / 90m / 1w} —— 纯算术，由这里精确算，误差为零；</li>
 *   <li>{@code remindAt}（绝对）：{@code 2026-11-28 20:00} 这类"日期 + 时刻"，模型只需要抄日期，不用减天数。</li>
 * </ul>
 * 两个都给时以 {@code remindIn} 为准（相对说法更不容易错）。
 *
 * <p><b>过去的时间一律拒绝</b>，不自动往后挪：用户说的是"明天八点"，模型算成今天八点，
 * 悄悄改成明天八点看起来"修好了"，实际是拿猜测覆盖了用户的话。宁可如实说"这个时间已经过了"。
 */
public final class ReminderTime {

    private ReminderTime() {
    }

    /**
     * 相对说法的粒度：只用这几个单位，别的（"一会儿""晚点"）一律不猜。
     *
     * <p>{@code mo/month/月} 必须排在 {@code m} 前面，否则 {@code 2mo} 会被当成 2 分钟匹配掉。
     */
    private static final Pattern RELATIVE = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(d|day|days|天|h|hour|hours|小时|mo|month|months|月|w|week|weeks|周|星期|m|min|mins|minute|minutes|分钟|分|s|sec|secs|second|seconds|秒)");

    /** 2026-11-28 20:00 / 2026/11/28 20:00:00 / 2026年11月28日 20点 */
    private static final Pattern ABSOLUTE_DATE = Pattern.compile(
            "^(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})(?:\\s*(\\d{1,2})[:：](\\d{1,2})(?:[:：](\\d{1,2}))?)?$");

    /** 11-28 20:00（没年份：默认今年，若已过去则算明年） */
    private static final Pattern MONTH_DAY = Pattern.compile(
            "^(\\d{1,2})[-/.](\\d{1,2})(?:\\s*(\\d{1,2})[:：](\\d{1,2}))?$");

    /** 20:00（没日期：今天这个点，已过则明天） */
    private static final Pattern CLOCK_ONLY = Pattern.compile(
            "^(\\d{1,2})[:：](\\d{1,2})(?:[:：](\\d{1,2}))?$");

    /** 只有日期没给时刻时的默认时刻：早上 9 点（比 0 点更接近"提醒"的语义）。 */
    private static final int DEFAULT_HOUR = 9;

    private static final long MAX_AHEAD_MS = 5L * 365 * 24 * 60 * 60 * 1000;

    /**
     * 解析结果。
     *
     * @param atMillis    提醒时刻
     * @param relativeRaw 命中的相对说法原文（没给就是空串），回话时可以用它复述"三天后"
     */
    public record Resolved(long atMillis, String relativeRaw) {
    }

    /**
     * @param remindAt  绝对时间说法（可为空）
     * @param remindIn  相对时间说法（可为空；非空时优先）
     * @param nowMillis 现在（显式传入，测试才好固定时间）
     * @param zone      用户所在时区
     * @throws IllegalArgumentException 两种说法都解析不出、或落到了过去/太远的地方
     */
    public static Resolved resolve(String remindAt, String remindIn, long nowMillis, ZoneId zone) {
        LocalDateTime now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone);
        String relative = remindIn == null ? "" : remindIn.strip();

        long at;
        if (!relative.isEmpty()) {
            at = nowMillis + relativeMillis(relative);
        } else {
            String absolute = remindAt == null ? "" : remindAt.strip();
            if (absolute.isEmpty()) {
                throw new IllegalArgumentException("没有给出提醒时间");
            }
            at = absoluteMillis(absolute, now, zone);
        }

        if (at <= nowMillis - 60_000L) {
            throw new IllegalArgumentException("这个时间已经过去了（" + human(at, nowMillis, zone) + "）");
        }
        if (at > nowMillis + MAX_AHEAD_MS) {
            throw new IllegalArgumentException("这个时间太远了（" + human(at, nowMillis, zone) + "）");
        }
        return new Resolved(at, relative);
    }

    /** "3d" / "2h30m" / "90m" / "1周" → 毫秒。单位不认识的字符会被忽略，一个数都没取到就报错。 */
    static long relativeMillis(String raw) {
        String text = raw.toLowerCase().replace("个", "").strip();
        Matcher matcher = RELATIVE.matcher(text);
        double totalMs = 0;
        int hits = 0;
        while (matcher.find()) {
            double amount = Double.parseDouble(matcher.group(1));
            hits += 1;
            totalMs += switch (matcher.group(2)) {
                case "d", "day", "days", "天" -> amount * 24 * 60 * 60 * 1000L;
                case "h", "hour", "hours", "小时" -> amount * 60 * 60 * 1000L;
                case "mo", "month", "months", "月" -> amount * 30 * 24 * 60 * 60 * 1000L;
                case "w", "week", "weeks", "周", "星期" -> amount * 7 * 24 * 60 * 60 * 1000L;
                case "s", "sec", "secs", "second", "seconds", "秒" -> amount * 1000L;
                default -> amount * 60 * 1000L;
            };
        }
        if (hits == 0) {
            throw new IllegalArgumentException("看不懂这个时间间隔：" + raw);
        }
        if (totalMs <= 0) {
            throw new IllegalArgumentException("时间间隔必须大于 0：" + raw);
        }
        // 上限五年，挡住"9999 天"这类明显打错的说法
        return Math.min((long) totalMs, MAX_AHEAD_MS);
    }

    private static long absoluteMillis(String raw, LocalDateTime now, ZoneId zone) {
        // "晚上八点"里的"晚上"要先摘出来：它是 12 小时制的补数，不是噪音
        int hourDelta = hourDeltaOf(raw);
        // "明天/后天/大后天"是日期的偏移，也得先摘出来
        int dayOffset = dayOffsetOf(raw);
        String text = cnNumberToDigits(normalize(raw));
        Matcher date = ABSOLUTE_DATE.matcher(text);
        if (date.matches()) {
            return build(Integer.parseInt(date.group(1)), Integer.parseInt(date.group(2)),
                    Integer.parseInt(date.group(3)), date.group(4), date.group(5), date.group(6),
                    hourDelta, zone);
        }
        Matcher monthDay = MONTH_DAY.matcher(text);
        if (monthDay.matches()) {
            int month = Integer.parseInt(monthDay.group(1));
            int day = Integer.parseInt(monthDay.group(2));
            long candidate = build(now.getYear(), month, day,
                    monthDay.group(3), monthDay.group(4), null, hourDelta, zone);
            // 说的日期今年已经过了 → 按明年算（生日、纪念日都是这个语义）
            return candidate < toMillis(now, zone) ? build(now.getYear() + 1, month, day,
                    monthDay.group(3), monthDay.group(4), null, hourDelta, zone) : candidate;
        }
        // 只剩"明天"这种词、没带钟点 → 默认早上九点
        int hour = DEFAULT_HOUR;
        int minute = 0;
        int second = 0;
        Matcher clock = CLOCK_ONLY.matcher(text);
        if (clock.matches()) {
            hour = Integer.parseInt(clock.group(1)) + hourDelta;
            minute = Integer.parseInt(clock.group(2));
            second = clock.group(3) == null ? 0 : Integer.parseInt(clock.group(3));
        } else if (!text.isEmpty()) {
            throw new IllegalArgumentException("看不懂这个时间：" + raw);
        }
        long candidate = toMillis(now.withHour(hour).withMinute(minute).withSecond(second)
                .withNano(0), zone) + dayOffset * 24L * 60 * 60 * 1000;
        // 只说"八点"而今天八点已经过了 → 说的是明天早上八点（"明天八点"的 dayOffset 已经排除了这种情况）
        return candidate < toMillis(now, zone) ? candidate + 24 * 60 * 60 * 1000L : candidate;
    }

    private static int dayOffsetOf(String raw) {
        if (raw.contains("大后天")) {
            return 3;
        }
        if (raw.contains("后天")) {
            return 2;
        }
        if (raw.contains("明天") || raw.contains("明早") || raw.contains("明晚")) {
            return 1;
        }
        return 0;
    }

    /**
     * 中文时段词 → 小时补数。"晚上八点"= 20:00，"下午三点"= 15:00。
     *
     * <p>只在模型把时段词带进 remindAt 时才生效；它按约定给 {@code 2026-11-28 20:00} 就走不到这里。
     */
    private static int hourDeltaOf(String raw) {
        if (raw.contains("下午") || raw.contains("傍晚") || raw.contains("晚上") || raw.contains("夜里")
                || raw.contains("晚")) {
            return 12;
        }
        return 0;
    }

    /** 时段词本身不是时间，解析前要抹掉，否则正则匹配不上。长的排前面，避免"晚"把"晚上"吃掉。 */
    private static final String[] PERIOD_WORDS = {
            "大后天", "大前天", "后天", "前天", "明天", "今天", "今晚", "明晚", "明早", "今早",
            "凌晨", "早上", "早晨", "上午", "中午", "下午", "傍晚", "晚上", "夜里", "晚"
    };

    /** 统一成 {@code yyyy-M-d H:m} 这种干净形态，让上面几个正则只要处理一种写法。 */
    private static String normalize(String raw) {
        String text = raw.strip()
                .replace('T', ' ')
                .replaceAll("\\s+", " ")
                .replace("年", "-")
                .replace("月", "-")
                .replace("日", " ")
                .replace("号", " ")
                .replace("点", ":")
                .replace("时", ":")
                .replace("半", "30")
                .replace("分", "");
        for (String word : PERIOD_WORDS) {
            text = text.replace(word, " ");
        }
        text = text.replaceAll("\\s+", " ").strip();
        // "八点" → "8:"，缺分钟补 00；"八点半" 已经变成 "8:30" 不受影响
        if (text.endsWith(":")) {
            text = text + "00";
        }
        return text;
    }

    private static final java.util.Map<Character, Integer> CN_DIGITS = java.util.Map.ofEntries(
            java.util.Map.entry('零', 0), java.util.Map.entry('一', 1), java.util.Map.entry('二', 2),
            java.util.Map.entry('两', 2), java.util.Map.entry('三', 3), java.util.Map.entry('四', 4),
            java.util.Map.entry('五', 5), java.util.Map.entry('六', 6), java.util.Map.entry('七', 7),
            java.util.Map.entry('八', 8), java.util.Map.entry('九', 9));

    /**
     * 把中文数字换成阿拉伯数字：{@code 晚上八点 → 晚上8:}、{@code 二十三点 → 23:}。
     *
     * <p>模型按约定会给 ISO 格式，这里只是兜底 —— 但它把用户原话"晚上八点"原样抄进 remindAt
     * 的时候，不能因为这个就解析失败。
     */
    private static String cnNumberToDigits(String text) {
        Matcher matcher = Pattern.compile("[零一二两三四五六七八九十]+").matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(String.valueOf(cnToInt(matcher.group()))));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** 十以内、十几、几十、几十几 都覆盖；更大的中文数（百/千）不常见，放弃。 */
    private static int cnToInt(String token) {
        if (token.length() == 1) {
            Integer value = CN_DIGITS.get(token.charAt(0));
            if (value != null) {
                return value;
            }
            return token.charAt(0) == '十' ? 10 : 0;
        }
        if (token.charAt(0) == '十') {
            // 十一 ~ 十九
            return 10 + CN_DIGITS.getOrDefault(token.charAt(1), 0);
        }
        if (token.charAt(token.length() - 1) == '十') {
            // 二十 ~ 九十
            return CN_DIGITS.getOrDefault(token.charAt(0), 1) * 10;
        }
        // 二十三
        return CN_DIGITS.getOrDefault(token.charAt(0), 0) * 10
                + CN_DIGITS.getOrDefault(token.charAt(token.length() - 1), 0);
    }

    private static long build(int year, int month, int day, String hourRaw, String minuteRaw,
            String secondRaw, int hourDelta, ZoneId zone) {
        int hour = hourRaw == null ? DEFAULT_HOUR : Integer.parseInt(hourRaw);
        // "晚上 8 点" 才补 12；他要是写了 20 点就别再补一次
        hour = hour < 12 ? hour + hourDelta : hour;
        int minute = minuteRaw == null ? 0 : Integer.parseInt(minuteRaw);
        int second = secondRaw == null ? 0 : Integer.parseInt(secondRaw);
        return toMillis(LocalDateTime.of(year, month, day, hour, minute, second), zone);
    }

    private static long toMillis(LocalDateTime time, ZoneId zone) {
        return time.atZone(zone).toInstant().toEpochMilli();
    }

    /**
     * 给人看的时间：今天/明天/后天用相对词，更远就报日期。
     * 提醒确认、"我记下了"这类回话都靠它，用户一眼能核对对错。
     */
    public static String human(long atMillis, long nowMillis, ZoneId zone) {
        LocalDateTime at = LocalDateTime.ofInstant(Instant.ofEpochMilli(atMillis), zone);
        LocalDateTime now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone);
        String clock = at.format(DateTimeFormatter.ofPattern("HH:mm"));
        long days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), at.toLocalDate());
        String day = switch ((int) days) {
            case 0 -> "今天";
            case 1 -> "明天";
            case 2 -> "后天";
            default -> at.getMonthValue() + " 月 " + at.getDayOfMonth() + " 日";
        };
        String[] week = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        String suffix = at.getYear() == now.getYear() ? ""
                : "（" + at.getYear() + " 年）";
        return day + " " + clock + " · " + week[at.getDayOfWeek().getValue() - 1] + suffix;
    }
}
