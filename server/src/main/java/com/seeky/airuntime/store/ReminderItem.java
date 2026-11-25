package com.seeky.airuntime.store;

/**
 * 一条提醒。
 *
 * <p>三个核心字段就是需求里的那三项：{@code remindAt}（什么时候，毫秒时间戳）、
 * {@code kind}（什么类型）、{@code content}（提醒什么）。其余字段服务于"这条承诺有没有兑现"：
 * 属于谁（userId）、在哪句话里说的（sessionId）、到期了没有送出去（status / dueAt / deliveredAt）。
 *
 * @param remindAt  提醒时刻（epoch millis）。存绝对值而不是"3 天后"这种相对量 ——
 *                  相对量要每次重新算，用户改了说法就对不上了
 * @param remindIn  用户原话里的相对说法（"3d" / "2h"），只用于回话时复述，不参与判定
 * @param status    pending（还没到点）/ due（到点了，等他看到）/ delivered（他已经看到）/
 *                  cancelled（他自己取消的）/ missed（到点太久没人看，作废）
 */
public record ReminderItem(
        String id,
        String userId,
        String sessionId,
        String kind,
        String content,
        long remindAt,
        String remindIn,
        String timezone,
        String status,
        long createdAt,
        Long dueAt,
        Long deliveredAt,
        Long cancelledAt) {
}
