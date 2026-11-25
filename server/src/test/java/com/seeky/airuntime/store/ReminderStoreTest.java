package com.seeky.airuntime.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 提醒的落库、状态机与隔离 —— 连真实 PG（测试库 {@code ai-soulmate-test}）。
 *
 * <p>状态机是关键：pending → due → delivered / missed / cancelled。它决定了
 * "三天后提醒我"到底会不会在第三天真的被推到用户面前，所以必须连真库验。
 */
class ReminderStoreTest {

    private static final String URL = System.getProperty("test.pg.url",
            "jdbc:postgresql://127.0.0.1:5432/ai-soulmate-test");

    private static PgStore db;
    private static ReminderStore store;

    private static final String ALICE = "u_alice";
    private static final String BOB = "u_bob";

    @BeforeAll
    static void connect() {
        db = new PgStore(URL, System.getProperty("test.pg.user", "zlj"), "", 2);
        db.init();
        store = new ReminderStore(db);
    }

    @AfterAll
    static void close() {
        db.close();
    }

    @BeforeEach
    void clean() {
        db.update("DELETE FROM reminders");
    }

    private long now() {
        return System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ 落库与查询

    @Test
    @DisplayName("create 后 upcoming 按时间正序返回，先到期的在前")
    void upcomingIsTimeOrdered() {
        long base = now();
        store.create(ALICE, "s1", "medication", "吃药", base + 3600_000, "1h", "Asia/Shanghai");
        store.create(ALICE, "s1", "todo", "交房租", base + 600_000, "10m", "Asia/Shanghai");

        List<ReminderItem> items = store.upcoming(ALICE, 10);

        assertThat(items).hasSize(2);
        assertThat(items.get(0).content()).isEqualTo("交房租");
        assertThat(items.get(1).content()).isEqualTo("吃药");
    }

    @Test
    @DisplayName("upcoming 只回 pending + due，不含已取消/已送达")
    void upcomingSkipsFinished() {
        long base = now();
        String done = store.create(ALICE, "s1", "todo", "已取消的", base + 1000, null, "Asia/Shanghai");
        store.create(ALICE, "s1", "todo", "还在等的", base + 2000, null, "Asia/Shanghai");
        store.cancelById(ALICE, done);

        List<ReminderItem> items = store.upcoming(ALICE, 10);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).content()).isEqualTo("还在等的");
    }

    // ------------------------------------------------------------------ 状态机

    @Test
    @DisplayName("markDue 只翻到点的，没到点的不动")
    void markDueOnlyFiresExpired() {
        long base = now();
        store.create(ALICE, "s1", "todo", "到点了", base - 1000, null, "Asia/Shanghai");
        store.create(ALICE, "s1", "todo", "还没到", base + 60_000, null, "Asia/Shanghai");

        int flipped = store.markDue(base);

        assertThat(flipped).isEqualTo(1);
        assertThat(store.due(ALICE)).hasSize(1);
        assertThat(store.due(ALICE).get(0).content()).isEqualTo("到点了");
    }

    @Test
    @DisplayName("ack 把 due 翻成 delivered，且重复 ack 不再生效")
    void ackDeliversOnce() {
        long base = now();
        String id = store.create(ALICE, "s1", "todo", "吃药", base - 1000, null, "Asia/Shanghai");
        store.markDue(base);

        assertThat(store.ack(ALICE, id)).isTrue();
        assertThat(store.due(ALICE)).isEmpty();
        assertThat(store.ack(ALICE, id)).isFalse();   // 已经不是 due 了
        assertThat(store.find(ALICE, id).status()).isEqualTo(ReminderStore.STATUS_DELIVERED);
    }

    @Test
    @DisplayName("到点太久没人看 → 作废为 missed")
    void staleDueIsMissed() {
        long base = now();
        String id = store.create(ALICE, "s1", "todo", "早就过了", base - 10_000, null, "Asia/Shanghai");
        store.markDue(base - 10_000);
        // 把 due_at 手动拨到两天前，模拟"到点两天没人看"
        db.update("UPDATE reminders SET due_at = ? WHERE id = ?", base - 2L * 86400_000, id);

        int missed = store.expireStale(base);

        assertThat(missed).isEqualTo(1);
        assertThat(store.find(ALICE, id).status()).isEqualTo(ReminderStore.STATUS_MISSED);
    }

    // ------------------------------------------------------------------ 取消

    @Test
    @DisplayName("按关键词取消：找到就取消，找不到返回 null")
    void cancelByHint() {
        long base = now();
        store.create(ALICE, "s1", "medication", "吃两片维C", base + 1000, null, "Asia/Shanghai");

        assertThat(store.cancel(ALICE, "维C")).isNotNull();
        assertThat(store.cancel(ALICE, "跑步")).isNull();
    }

    @Test
    @DisplayName("hint 为空取消最早一条")
    void cancelBlankHintTakesEarliest() {
        long base = now();
        store.create(ALICE, "s1", "todo", "早的", base + 1000, null, "Asia/Shanghai");
        store.create(ALICE, "s1", "todo", "晚的", base + 2000, null, "Asia/Shanghai");

        ReminderItem cancelled = store.cancel(ALICE, "");

        assertThat(cancelled.content()).isEqualTo("早的");
        assertThat(store.upcoming(ALICE, 10)).extracting(ReminderItem::content).containsExactly("晚的");
    }

    @Test
    @DisplayName("cancelById 精确取消，别的用户取消同一条无效")
    void cancelByIdIsScoped() {
        long base = now();
        String id = store.create(ALICE, "s1", "todo", "A 的", base + 1000, null, "Asia/Shanghai");

        assertThat(store.cancelById(BOB, id)).isNull();
        assertThat(store.cancelById(ALICE, id)).isNotNull();
    }

    // ------------------------------------------------------------------ 隔离

    @Test
    @DisplayName("跨用户查不到、也投递不到别人的提醒")
    void fullyIsolatedByUser() {
        long base = now();
        store.create(ALICE, "s1", "todo", "只有 A 该看到", base - 1000, null, "Asia/Shanghai");

        assertThat(store.due(BOB)).isEmpty();
        assertThat(store.upcoming(BOB, 10)).isEmpty();
    }

    @Test
    @DisplayName("created_at 是毫秒时间戳，早于/等于现在")
    void createdAtIsSane() {
        String id = store.create(ALICE, "s1", "todo", "时间戳", now() + 1000, null, "Asia/Shanghai");
        assertThat(store.find(ALICE, id).createdAt())
                .isLessThanOrEqualTo(Instant.now().toEpochMilli());
    }
}
