package com.seeky.airuntime.store;

import com.seeky.airuntime.common.BusinessException;
import com.seeky.airuntime.common.ErrorCode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL 存储（本机 5432，库名 {@code ai-soulmate}）。
 *
 * <p><b>这里只放业务数据</b>：会话（{@code sessions}）与消息（{@code messages}）。
 * 长期记忆一律不落这里 —— 记忆的唯一权威是 mem0（本机 memory-service），
 * 所以没有 facts / fact_events，记忆的增删改查与变更史都通过 {@code Mem0Client} 走 HTTP。
 *
 * <p>连接管理：PostgreSQL 是多连接模型，用 HikariCP 池子，每个操作借一条、用完归还。
 * 池子只有几条连接（单用户 Demo，峰值也就前端 + 一个写线程），不设 {@code synchronized}——
 * 并发由数据库自己处理，比在 Java 侧串行化更合理（也不会因为某条 SQL 慢了把整个应用卡住）。
 *
 * <p>为什么不用 JPA/Flyway：需求是"怎么简单怎么来"，建表 SQL 写在 {@link #init()} 里，
 * 改结构直接 {@code CREATE TABLE IF NOT EXISTS} + 手工迁移，比引 Flyway 少一层依赖。
 */
@Component
public class PgStore {

    private static final Logger log = LoggerFactory.getLogger(PgStore.class);

    private final String url;
    private final String username;
    private final String password;
    private final int poolSize;
    private HikariDataSource dataSource;

    public PgStore(
            @Value("${seeky.datasource.url:jdbc:postgresql://127.0.0.1:5432/ai-soulmate}") String url,
            @Value("${seeky.datasource.username:postgres}") String username,
            @Value("${seeky.datasource.password:}") String password,
            @Value("${seeky.datasource.pool-size:4}") int poolSize) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.poolSize = poolSize;
    }

    @PostConstruct
    public void init() {
        try {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(url);
            cfg.setUsername(username);
            cfg.setPassword(password == null || password.isEmpty() ? null : password);
            cfg.setMaximumPoolSize(Math.max(1, poolSize));
            cfg.setMinimumIdle(1);
            cfg.setPoolName("soulmate-pg");
            // 本机库，连接建立快；给 5s 足够暴露"库没起/库名写错"
            cfg.setConnectionTimeout(5_000);
            // 服务端重启或空闲被踢后，池子要能自愈
            cfg.setValidationTimeout(2_000);
            cfg.setKeepaliveTime(30_000);
            dataSource = new HikariDataSource(cfg);
            createSchema();
            log.info("PostgreSQL 已就绪：{}（user={}, pool={}）", url, username, cfg.getMaximumPoolSize());
        } catch (Exception ex) {
            closeQuietly();
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "PostgreSQL 初始化失败：" + ex.getMessage(), ex);
        }
    }

    @PreDestroy
    public void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            if (dataSource != null && !dataSource.isClosed()) {
                dataSource.close();
            }
        } catch (Exception ignored) {
            // 退出阶段关不掉不影响正确性
        }
    }

    private void createSchema() throws SQLException {
        try (Connection cn = dataSource.getConnection(); Statement st = cn.createStatement()) {
            // 会话：首屏要「上次聊到什么时候」来判断待回访，靠 last_active_at 直接读，不用扫消息表
            // 时间戳统一用 BIGINT 存毫秒（epoch millis）—— PG 的 INTEGER 是 32 位，装不下
            st.execute("""
                    CREATE TABLE IF NOT EXISTS sessions (
                      id             TEXT PRIMARY KEY,
                      user_id        TEXT NOT NULL,
                      title          TEXT NOT NULL DEFAULT '',
                      started_at     BIGINT NOT NULL,
                      last_active_at BIGINT NOT NULL,
                      message_count  INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id, last_active_at DESC)");

            // seq 只是同一毫秒内的稳定排序（原来 SQLite 用隐式 rowid，PG 没有，给一条自增列顶上）
            st.execute("""
                    CREATE TABLE IF NOT EXISTS messages (
                      id          TEXT PRIMARY KEY,
                      session_id  TEXT NOT NULL,
                      user_id     TEXT NOT NULL,
                      role        TEXT NOT NULL,
                      text        TEXT NOT NULL DEFAULT '',
                      media_json  TEXT,
                      created_at  BIGINT NOT NULL,
                      seq         BIGSERIAL
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id, created_at)");

            // 用户：登录名不分大小写唯一（Alice 和 alice 是同一个人，否则注册能绕过唯一约束）；
            // password_hash 存 PBKDF2 结果（见 PasswordHasher），这里不存明文、也不存盐的单独字段
            st.execute("""
                    CREATE TABLE IF NOT EXISTS users (
                      id            TEXT PRIMARY KEY,
                      username      TEXT NOT NULL,
                      password_hash TEXT NOT NULL,
                      nickname      TEXT NOT NULL DEFAULT '',
                      enabled       BOOLEAN NOT NULL DEFAULT TRUE,
                      created_at    BIGINT NOT NULL,
                      updated_at    BIGINT NOT NULL,
                      last_login_at BIGINT
                    )
                    """);
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_users_username ON users (lower(username))");

            // 提醒：用户说"三天后提醒我吃药"，这里落一条到期要兑现的承诺。
            // 三件事必须记全 —— 什么时候提醒（remind_at）、提醒什么类型（kind）、提醒什么内容（content）；
            // 其余字段都是为了让这条承诺可追踪：属于谁、在哪句话里说的、到期有没有真的送到。
            // 时间统一存 epoch millis（与 sessions/messages 一致），时区只在解析/展示时参与，
            // 避免"服务器换时区，所有提醒整体平移 8 小时"这种只有用户能发现的错。
            st.execute("""
                    CREATE TABLE IF NOT EXISTS reminders (
                      id           TEXT PRIMARY KEY,
                      user_id      TEXT NOT NULL,
                      session_id   TEXT,
                      kind         TEXT NOT NULL DEFAULT 'other',
                      content      TEXT NOT NULL,
                      remind_at    BIGINT NOT NULL,
                      remind_in    TEXT,
                      timezone     TEXT NOT NULL DEFAULT 'Asia/Shanghai',
                      status       TEXT NOT NULL DEFAULT 'pending',
                      created_at   BIGINT NOT NULL,
                      due_at       BIGINT,
                      delivered_at BIGINT,
                      cancelled_at BIGINT
                    )
                    """);
            // 扫描器只查 pending 里到点的，这条索引让它永远是一次索引定位，而不是全表扫
            st.execute("CREATE INDEX IF NOT EXISTS idx_reminders_due ON reminders(status, remind_at)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_reminders_user ON reminders(user_id, remind_at DESC)");
        }
    }

    /**
     * 行映射器：允许抛 {@link SQLException}。
     *
     * <p>不用 {@code java.util.function.Function} 是因为 {@code rs.getString(...)} 会抛受检异常，
     * 那样每个调用点都得套一层 try/catch，或者把异常吞成 {@code RuntimeException} 丢掉原因。
     */
    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    /** 读：把 ResultSet 逐行映射成对象。 */
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
        try (Connection cn = dataSource.getConnection();
                PreparedStatement ps = cn.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapper.map(rs));
                }
                return rows;
            }
        } catch (SQLException ex) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "PostgreSQL 查询失败：" + ex.getMessage(), ex);
        }
    }

    /** 写：返回受影响行数。 */
    public int update(String sql, Object... args) {
        try (Connection cn = dataSource.getConnection();
                PreparedStatement ps = cn.prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        } catch (SQLException ex) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "PostgreSQL 写入失败：" + ex.getMessage(), ex);
        }
    }

    private void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i += 1) {
            ps.setObject(i + 1, args[i]);
        }
    }
}
