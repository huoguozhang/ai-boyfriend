package com.seeky.airuntime.store;

import com.seeky.airuntime.common.BusinessException;
import com.seeky.airuntime.common.ErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 会话与消息的读写（PG 里只有这两张表，记忆不在这里）。
 *
 * <p>{@code sessions.last_active_at} 是「主动回访」的判断依据：她要不要先开口，
 * 看的是上一次对话结束多久，不需要扫整张消息表。
 *
 * <p>要历史给模型时按「最近 N 条 + 时间正序」返回：SQL 先倒序取，再在内存里翻过来——
 * 直接正序 LIMIT 拿到的是最早的 N 条，模型会以为你们还停在两天前。
 */
@Component
public class ChatStore {

    private final PgStore db;

    public ChatStore(PgStore db) {
        this.db = db;
    }

    /**
     * 会话不存在就建一条；存在则只刷新活跃时间 —— <b>并顺手做归属校验</b>。
     *
     * <p>{@code ON CONFLICT ... DO UPDATE ... WHERE sessions.user_id = EXCLUDED.user_id}
     * 是关键：id 撞上了但属主不是当前用户时，这条 UPDATE 的 WHERE 不成立，
     * 整条语句影响 0 行。于是"有没有权限"和"写没写进去"变成同一件事，天然抗并发
     * （先查属主再写会留下 TOCTOU 窗口 —— 两个用户同时拿同一个 sessionId 打进来）。
     *
     * @throws BusinessException 4403 会话属于别的用户
     */
    public void ensureSession(String sessionId, String userId) {
        long now = Instant.now().toEpochMilli();
        int affected = db.update("""
                INSERT INTO sessions (id, user_id, title, started_at, last_active_at, message_count)
                VALUES (?, ?, '', ?, ?, 0)
                ON CONFLICT (id) DO UPDATE SET last_active_at = EXCLUDED.last_active_at
                WHERE sessions.user_id = EXCLUDED.user_id
                """, sessionId, userId, now, now);
        if (affected == 0) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该会话");
        }
    }

    /** 这个用户最近活跃的会话 id；一条都没有时返回 null。 */
    public String latestSessionId(String userId) {
        List<String> rows = db.query(
                "SELECT id FROM sessions WHERE user_id = ? ORDER BY last_active_at DESC LIMIT 1",
                rs -> rs.getString("id"), userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 追加一条消息，并维护会话的消息数与活跃时间。 */
    public String append(String sessionId, String userId, String role, String text, String mediaJson) {
        String id = UUID.randomUUID().toString();
        long now = Instant.now().toEpochMilli();
        db.update("""
                INSERT INTO messages (id, session_id, user_id, role, text, media_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, sessionId, userId, role, text == null ? "" : text, mediaJson, now);
        db.update("""
                UPDATE sessions SET last_active_at = ?, message_count = message_count + 1 WHERE id = ?
                """, now, sessionId);
        return id;
    }

    /** 最近 limit 条，按时间正序返回，可直接喂给模型。userId 是兜底隔离，防止串会话。 */
    public List<Map<String, String>> recent(String sessionId, String userId, int limit) {
        List<Map<String, String>> rows = db.query("""
                SELECT role, text FROM messages
                WHERE session_id = ? AND user_id = ? AND text <> ''
                ORDER BY created_at DESC, seq DESC
                LIMIT ?
                """, rs -> {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("role", rs.getString("role"));
            row.put("content", rs.getString("text"));
            return row;
        }, sessionId, userId, limit);

        List<Map<String, String>> ordered = new ArrayList<>(rows);
        java.util.Collections.reverse(ordered);
        return ordered;
    }

    /** 首屏历史（前端渲染气泡用），时间正序。userId 是兜底隔离，防止串会话。 */
    public List<Map<String, Object>> history(String sessionId, String userId, int limit) {
        List<Map<String, Object>> rows = db.query("""
                SELECT id, role, text, media_json, created_at FROM messages
                WHERE session_id = ? AND user_id = ?
                ORDER BY created_at DESC, seq DESC
                LIMIT ?
                """, rs -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getString("id"));
            row.put("role", rs.getString("role"));
            row.put("text", rs.getString("text"));
            row.put("media", rs.getString("media_json"));
            row.put("createdAt", Instant.ofEpochMilli(rs.getLong("created_at")).toString());
            return row;
        }, sessionId, userId, limit);

        List<Map<String, Object>> ordered = new ArrayList<>(rows);
        java.util.Collections.reverse(ordered);
        return ordered;
    }

    /** 上次活动时间（毫秒），没有会话返回 0。 */
    public long lastActiveAt(String sessionId) {
        List<Long> rows = db.query(
                "SELECT last_active_at FROM sessions WHERE id = ?",
                rs -> rs.getLong("last_active_at"), sessionId);
        return rows.isEmpty() ? 0L : rows.get(0);
    }
}
