package com.seeky.airuntime.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 用户表的读写。表结构在 {@link PgStore#init()} 里建，这里只管 CRUD。
 *
 * <p><b>用户名不分大小写</b>：唯一索引建在 {@code lower(username)} 上，查询也一律
 * {@code lower(username) = lower(?)}。不然 {@code Alice} 和 {@code alice} 会被当成两个人，
 * 注册时的"是否已存在"判断和数据库的唯一约束就对不上了。
 *
 * <p>{@link UserRow#passwordHash()} 只给登录校验用，任何接口都不该把它带出去 ——
 * 对外一律走 {@code dto.AuthDtos.AuthUser}。
 */
@Component
public class UserStore {

    private final PgStore db;

    public UserStore(PgStore db) {
        this.db = db;
    }

    /** 一行用户。{@code lastLoginAt=0} 表示从没登录过。 */
    public record UserRow(
            String id,
            String username,
            String nickname,
            String passwordHash,
            boolean enabled,
            long createdAt,
            long lastLoginAt) {
    }

    private static final String COLUMNS =
            "id, username, nickname, password_hash, enabled, created_at, last_login_at";

    private static UserRow map(ResultSet rs) throws SQLException {
        long lastLogin = rs.getLong("last_login_at");
        boolean lastLoginIsNull = rs.wasNull();
        return new UserRow(
                rs.getString("id"),
                rs.getString("username"),
                rs.getString("nickname"),
                rs.getString("password_hash"),
                rs.getBoolean("enabled"),
                rs.getLong("created_at"),
                lastLoginIsNull ? 0L : lastLogin);
    }

    public Optional<UserRow> findByUsername(String username) {
        List<UserRow> rows = db.query(
                "SELECT " + COLUMNS + " FROM users WHERE lower(username) = lower(?)",
                UserStore::map, username);
        return rows.stream().findFirst();
    }

    public Optional<UserRow> findById(String id) {
        List<UserRow> rows = db.query(
                "SELECT " + COLUMNS + " FROM users WHERE id = ?",
                UserStore::map, id);
        return rows.stream().findFirst();
    }

    public boolean existsByUsername(String username) {
        return !db.query("SELECT 1 FROM users WHERE lower(username) = lower(?)",
                rs -> Boolean.TRUE, username).isEmpty();
    }

    /** 插入新用户；用户名撞车由唯一索引拦下（并发注册时靠它兜底）。 */
    public void insert(String id, String username, String passwordHash, String nickname, long now) {
        db.update("""
                INSERT INTO users (id, username, password_hash, nickname, enabled, created_at, updated_at)
                VALUES (?, ?, ?, ?, TRUE, ?, ?)
                """, id, username, passwordHash, nickname, now, now);
    }

    /** 登录成功后刷新最后登录时间。 */
    public void touchLogin(String id, long now) {
        db.update("UPDATE users SET last_login_at = ?, updated_at = ? WHERE id = ?", now, now, id);
    }
}
