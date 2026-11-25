package com.seeky.airuntime.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seeky.airuntime.common.BusinessException;
import com.seeky.airuntime.common.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 会话归属与数据隔离 —— 连真实的本地 PG（测试库 {@code ai-soulmate-test}）。
 *
 * <p>为什么不用 H2 / 不 mock：这里要验的恰恰是 SQL 本身的行为，
 * 尤其是 {@code INSERT ... ON CONFLICT (id) DO UPDATE ... WHERE sessions.user_id = EXCLUDED.user_id}
 * 这条"写不进去就等于没权限"的原子判断 —— 换成内存库或 mock 就测不到它了。
 *
 * <p>测试库由 psql 预先创建（见 README 的测试说明），每次跑前把三张表清空。
 */
class ChatStoreTest {

    private static final String URL = System.getProperty("test.pg.url",
            "jdbc:postgresql://127.0.0.1:5432/ai-soulmate-test");

    private static PgStore db;
    private static ChatStore store;

    private static final String ALICE = "u_alice";
    private static final String BOB = "u_bob";

    @BeforeAll
    static void connect() {
        db = new PgStore(URL, System.getProperty("test.pg.user", "zlj"), "", 2);
        db.init();
        store = new ChatStore(db);
    }

    @AfterAll
    static void close() {
        db.close();
    }

    @BeforeEach
    void clean() {
        db.update("DELETE FROM messages");
        db.update("DELETE FROM sessions");
    }

    private static String newSessionId() {
        return "s_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    // ------------------------------------------------------------------ 归属

    @Test
    @DisplayName("首次 ensureSession 建出一条会话，归属正确")
    void ensureSessionCreatesOwnedSession() {
        String sid = newSessionId();

        store.ensureSession(sid, ALICE);

        assertThat(store.latestSessionId(ALICE)).isEqualTo(sid);
        assertThat(store.latestSessionId(BOB)).isNull();
    }

    @Test
    @DisplayName("同一个用户重复 ensureSession 不报错、不建重复行，只刷新活跃时间")
    void ensureSessionIsIdempotentForOwner() {
        String sid = newSessionId();

        store.ensureSession(sid, ALICE);
        long first = db.query("SELECT last_active_at FROM sessions WHERE id = ?",
                rs -> rs.getLong("last_active_at"), sid).get(0);

        sleepTick();
        store.ensureSession(sid, ALICE);

        long second = db.query("SELECT last_active_at FROM sessions WHERE id = ?",
                rs -> rs.getLong("last_active_at"), sid).get(0);
        int rows = db.query("SELECT count(*) AS c FROM sessions WHERE id = ?",
                rs -> rs.getInt("c"), sid).get(0);

        assertThat(rows).isEqualTo(1);
        assertThat(second).isGreaterThanOrEqualTo(first);
    }

    @Test
    @DisplayName("别的用户拿同一个 sessionId 打进来 → 4403，而且不留下任何脏数据")
    void ensureSessionRejectsOtherUser() {
        String sid = newSessionId();
        store.ensureSession(sid, ALICE);

        assertThatThrownBy(() -> store.ensureSession(sid, BOB))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        // 会话属主没被抢走
        String owner = db.query("SELECT user_id FROM sessions WHERE id = ?",
                rs -> rs.getString("user_id"), sid).get(0);
        assertThat(owner).isEqualTo(ALICE);
        // B 那边也没凭空多出一条
        assertThat(store.latestSessionId(BOB)).isNull();
    }

    @Test
    @DisplayName("两个用户并发抢同一个 sessionId：只有一个成功，另一个 4403")
    void concurrentClaimHasSingleWinner() throws Exception {
        String sid = newSessionId();
        java.util.concurrent.atomic.AtomicInteger ok = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger denied = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);

        for (String user : List.of(ALICE, BOB)) {
            pool.submit(() -> {
                try {
                    start.await();
                    store.ensureSession(sid, user);
                    ok.incrementAndGet();
                } catch (BusinessException e) {
                    denied.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThat(ok.get()).isEqualTo(1);
        assertThat(denied.get()).isEqualTo(1);
        assertThat(db.query("SELECT count(*) AS c FROM sessions", rs -> rs.getInt("c")).get(0))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ 数据隔离

    @Test
    @DisplayName("历史只返回本人的消息：A 的会话对 B 是空的")
    void historyIsScopedByUser() {
        String sid = newSessionId();
        store.ensureSession(sid, ALICE);
        store.append(sid, ALICE, "user", "我说的话", null);
        store.append(sid, ALICE, "assistant", "她的回话", null);

        assertThat(store.history(sid, ALICE, 10)).hasSize(2);
        assertThat(store.history(sid, BOB, 10)).isEmpty();
        assertThat(store.recent(sid, BOB, 10)).isEmpty();
    }

    @Test
    @DisplayName("recent 给模型的是时间正序，取的是最近 N 条（不是最早的 N 条）")
    void recentReturnsLatestInChronologicalOrder() {
        String sid = newSessionId();
        store.ensureSession(sid, ALICE);
        for (int i = 1; i <= 5; i++) {
            store.append(sid, ALICE, "user", "第" + i + "句", null);
            sleepTick();
        }

        List<Map<String, String>> recent = store.recent(sid, ALICE, 3);

        assertThat(recent).extracting(m -> m.get("content"))
                .containsExactly("第3句", "第4句", "第5句");
        assertThat(recent).allSatisfy(m -> assertThat(m).containsKeys("role", "content"));
    }

    @Test
    @DisplayName("空文本的消息不进历史（占位/图片附件不算对话）")
    void historySkipsEmptyText() {
        String sid = newSessionId();
        store.ensureSession(sid, ALICE);
        store.append(sid, ALICE, "user", "有字", null);
        store.append(sid, ALICE, "user", "", "{\"type\":\"image\"}");

        assertThat(store.recent(sid, ALICE, 10)).hasSize(1);
    }

    @Test
    @DisplayName("history 的 createdAt 是 ISO-8601，前端能直接 new Date()")
    void historyExposesIsoTimestamp() {
        String sid = newSessionId();
        store.ensureSession(sid, ALICE);
        store.append(sid, ALICE, "user", "hi", null);

        Object createdAt = store.history(sid, ALICE, 10).get(0).get("createdAt");

        assertThat(createdAt).isInstanceOf(String.class);
        assertThat(java.time.Instant.parse((String) createdAt)).isBefore(java.time.Instant.now().plusSeconds(5));
    }

    @Test
    @DisplayName("append 会把 sessions.message_count 加上去（主动回访靠它判断聊过没有）")
    void appendBumpsMessageCount() {
        String sid = newSessionId();
        store.ensureSession(sid, ALICE);
        store.append(sid, ALICE, "user", "一", null);
        store.append(sid, ALICE, "assistant", "二", null);

        int count = db.query("SELECT message_count FROM sessions WHERE id = ?",
                rs -> rs.getInt("message_count"), sid).get(0);

        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("latestSessionId 取的是最近活跃的那个，不是最早建的那个")
    void latestSessionIdPicksMostRecentActivity() {
        String older = newSessionId();
        String newer = newSessionId();
        store.ensureSession(older, ALICE);
        sleepTick();
        store.ensureSession(newer, ALICE);

        assertThat(store.latestSessionId(ALICE)).isEqualTo(newer);

        sleepTick();
        store.ensureSession(older, ALICE);   // 回到旧会话说话 → 它变成最近活跃
        assertThat(store.latestSessionId(ALICE)).isEqualTo(older);
    }

    @Test
    @DisplayName("陌生 sessionId 直接 append 不会写进去（没有会话行就没有归属）")
    void appendWithoutSessionWritesNothing() {
        String ghost = newSessionId();

        // append 本身不校验归属（由调用方的 ensureSession 兜着），但这里确认它不会凭空建国
        store.append(ghost, ALICE, "user", "无主消息", null);

        assertThat(db.query("SELECT count(*) AS c FROM sessions WHERE id = ?",
                rs -> rs.getInt("c"), ghost).get(0)).isZero();
    }

    /** 让相邻两次写入的毫秒时间戳不同，避免同一毫秒内排序不稳定。 */
    private static void sleepTick() {
        try {
            Thread.sleep(3);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
