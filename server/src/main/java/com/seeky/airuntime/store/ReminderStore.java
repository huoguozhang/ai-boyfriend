package com.seeky.airuntime.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 提醒的读写。
 *
 * <p>状态机（只有这五态，别再加）：
 * <pre>
 * pending ──到点──▶ due ──他看到并确认──▶ delivered
 *    │              └──超过 24h 没人看──▶ missed
 *    └──他取消──▶ cancelled
 * </pre>
 *
 * <p>{@code due} 不是一个内部中间态，而是<b>待投递队列</b>：到期扫描只负责把 pending 翻成 due，
 * 真正"送到他眼前"由前端拉 {@link #due} 再回调 {@link #ack} 完成。这样服务重启、用户关掉页面
 * 都不会把一次提醒弄丢 —— 他三天后回来，那条提醒还在队列里等着。
 *
 * <p>所有写操作都带 {@code user_id} 条件：拿别人的提醒 id 来取消，影响 0 行，等于什么都没有。
 */
@Component
public class ReminderStore {

    private static final Logger log = LoggerFactory.getLogger(ReminderStore.class);

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_DUE = "due";
    public static final String STATUS_DELIVERED = "delivered";
    public static final String STATUS_CANCELLED = "cancelled";
    public static final String STATUS_MISSED = "missed";

    /** due 之后多久还没被看到就作废：隔了一天才弹"该吃药了"是打扰，不是提醒。 */
    public static final long MISS_AFTER_MS = 24L * 60 * 60 * 1000;

    private final PgStore db;

    public ReminderStore(PgStore db) {
        this.db = db;
    }

    /**
     * 落一条提醒。
     *
     * @param remindAt 提醒时刻（epoch millis，已由 {@code ReminderTime} 解析成绝对时间）
     * @param remindIn 用户原话里的相对说法（"3d"/"2h"），只用于复述，不参与判定；可为 null
     * @return 新记录 id
     */
    public String create(String userId, String sessionId, String kind, String content,
            long remindAt, String remindIn, String timezone) {
        String id = "r_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        db.update("""
                INSERT INTO reminders (id, user_id, session_id, kind, content, remind_at, remind_in,
                                       timezone, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?)
                """, id, userId, sessionId, kind, content, remindAt, remindIn, timezone,
                Instant.now().toEpochMilli());
        log.info("[reminder] 新建 user={} id={} kind={} at={} content={}",
                userId, id, kind, Instant.ofEpochMilli(remindAt), content);
        return id;
    }

    /** 还没兑现的（pending + due），按时间正序：先到期的排前面。 */
    public List<ReminderItem> upcoming(String userId, int limit) {
        return db.query("""
                SELECT * FROM reminders
                WHERE user_id = ? AND status IN ('pending', 'due')
                ORDER BY remind_at ASC
                LIMIT ?
                """, ReminderStore::map, userId, limit);
    }

    /** 待投递队列：到点了但还没送到他眼前的。 */
    public List<ReminderItem> due(String userId) {
        return db.query("""
                SELECT * FROM reminders
                WHERE user_id = ? AND status = 'due'
                ORDER BY remind_at ASC
                """, ReminderStore::map, userId);
    }

    public ReminderItem find(String userId, String id) {
        List<ReminderItem> rows = db.query(
                "SELECT * FROM reminders WHERE id = ? AND user_id = ?",
                ReminderStore::map, id, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 取消。{@code hint} 为空表示"最近那条"，否则按内容模糊匹配。
     *
     * <p>为什么允许按内容找：他说的永远是"不用提醒我吃药了"，不会报 id。
     * 匹配不到就返回 null，由调用方如实说"没找到那条"，绝不假装取消成功。
     *
     * @return 被取消的那条；没有可取消的返回 null
     */
    public ReminderItem cancel(String userId, String hint) {
        String keyword = hint == null ? "" : hint.strip();
        List<ReminderItem> targets = keyword.isEmpty()
                ? db.query("""
                        SELECT * FROM reminders
                        WHERE user_id = ? AND status IN ('pending', 'due')
                        ORDER BY remind_at ASC LIMIT 1
                        """, ReminderStore::map, userId)
                : db.query("""
                        SELECT * FROM reminders
                        WHERE user_id = ? AND status IN ('pending', 'due') AND content ILIKE ?
                        ORDER BY remind_at ASC LIMIT 1
                        """, ReminderStore::map, userId, "%" + keyword + "%");
        if (targets.isEmpty()) {
            return null;
        }
        ReminderItem target = targets.get(0);
        int affected = db.update("""
                UPDATE reminders SET status = 'cancelled', cancelled_at = ?
                WHERE id = ? AND user_id = ? AND status IN ('pending', 'due')
                """, Instant.now().toEpochMilli(), target.id(), userId);
        return affected == 0 ? null : target;
    }

    /** 按 id 精确取消（列表页点某一条时用）。没有这条、或不是 pending/due 返回 null。 */
    public ReminderItem cancelById(String userId, String id) {
        ReminderItem target = find(userId, id);
        if (target == null || !(STATUS_PENDING.equals(target.status()) || STATUS_DUE.equals(target.status()))) {
            return null;
        }
        int affected = db.update("""
                UPDATE reminders SET status = 'cancelled', cancelled_at = ?
                WHERE id = ? AND user_id = ? AND status IN ('pending', 'due')
                """, Instant.now().toEpochMilli(), id, userId);
        return affected == 0 ? null : target;
    }

    /** 他看到了这条提醒（前端弹出来之后回调）。影响 0 行说明不是他的，或者已经被处理过。 */
    public boolean ack(String userId, String id) {
        return db.update("""
                UPDATE reminders SET status = 'delivered', delivered_at = ?
                WHERE id = ? AND user_id = ? AND status = 'due'
                """, Instant.now().toEpochMilli(), id, userId) > 0;
    }

    /**
     * 到期扫描：把到点的 pending 翻成 due。<b>一条 SQL 原子完成</b>，不需要先把行读出来再逐条改，
     * 所以两个实例同时扫也不会重复投递。
     *
     * @return 本次翻了多少条
     */
    public int markDue(long now) {
        return db.update("""
                UPDATE reminders SET status = 'due', due_at = ?
                WHERE status = 'pending' AND remind_at <= ?
                """, now, now);
    }

    /** 到点太久没人看的作废：避免用户隔了几天打开 App 被一堆过期提醒糊脸。 */
    public int expireStale(long now) {
        return db.update("""
                UPDATE reminders SET status = 'missed'
                WHERE status = 'due' AND due_at IS NOT NULL AND due_at <= ?
                """, now - MISS_AFTER_MS);
    }

    private static ReminderItem map(ResultSet rs) throws SQLException {
        return new ReminderItem(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("session_id"),
                rs.getString("kind"),
                rs.getString("content"),
                rs.getLong("remind_at"),
                rs.getString("remind_in"),
                rs.getString("timezone"),
                rs.getString("status"),
                rs.getLong("created_at"),
                nullableLong(rs, "due_at"),
                nullableLong(rs, "delivered_at"),
                nullableLong(rs, "cancelled_at"));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
