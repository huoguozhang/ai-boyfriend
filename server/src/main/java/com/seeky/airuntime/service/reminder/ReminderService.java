package com.seeky.airuntime.service.reminder;

import com.seeky.airuntime.store.ReminderItem;
import com.seeky.airuntime.store.ReminderStore;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 待办提醒的业务侧：把"他随口说的一句话里的时间"变成一条可兑现的记录。
 *
 * <p>为什么单独一层而不是全塞在工具里：同一件事有三个入口 —— 她对话时调工具、用户在提醒列表里看、
 * 到期扫描投递。三个入口必须用同一套时间口径（同一个时区、同一套复述文案），
 * 否则会出现"她自己说 8 点、列表里显示 7 点"这种自相矛盾。
 */
@Service
public class ReminderService {

    private final ReminderStore store;
    private final ZoneId zone;

    public ReminderService(ReminderStore store,
            @Value("${seeky.reminder.timezone:Asia/Shanghai}") String zone) {
        this.store = store;
        this.zone = ZoneId.of(zone);
    }

    public ZoneId zone() {
        return zone;
    }

    public long now() {
        return System.currentTimeMillis();
    }

    /** 新建成功后回给调用方的东西：id + 定下来的时刻 + 一句可以照着念的复述。 */
    public record Created(String id, ReminderKind kind, String content, long remindAt, String humanText) {
    }

    /**
     * 新建一条提醒。
     *
     * @throws IllegalArgumentException 时间解析不出来 / 落在过去 / 没说要提醒什么
     */
    public Created create(String userId, String sessionId, String kindRaw, String content,
            String remindAt, String remindIn) {
        String text = content == null ? "" : content.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("没说要提醒什么");
        }
        ReminderKind kind = ReminderKind.of(kindRaw);
        long now = now();
        ReminderTime.Resolved resolved = ReminderTime.resolve(remindAt, remindIn, now, zone);
        String id = store.create(userId, sessionId, kind.code(), text, resolved.atMillis(),
                resolved.relativeRaw(), zone.getId());
        return new Created(id, kind, text, resolved.atMillis(),
                ReminderTime.human(resolved.atMillis(), now, zone));
    }

    /** 还没兑现的提醒（时间正序）。 */
    public List<ReminderItem> upcoming(String userId, int limit) {
        return store.upcoming(userId, limit);
    }

    /** 到点待投递的。 */
    public List<ReminderItem> due(String userId) {
        return store.due(userId);
    }

    public ReminderItem cancel(String userId, String hint) {
        return store.cancel(userId, hint);
    }

    public ReminderItem cancelById(String userId, String id) {
        return store.cancelById(userId, id);
    }

    public boolean ack(String userId, String id) {
        return store.ack(userId, id);
    }

    /** 到期扫描（由定时任务调用，两条 SQL 各自原子）。 */
    public int sweep() {
        long now = now();
        int expired = store.expireStale(now);
        int due = store.markDue(now);
        if (expired > 0 || due > 0) {
            return due;
        }
        return 0;
    }

    /** 一行复述："明天 08:00 · 吃药 —— 吃点维C"。工具回给模型、列表给用户都用它。 */
    public String line(ReminderItem item) {
        ReminderKind kind = ReminderKind.of(item.kind());
        return ReminderTime.human(item.remindAt(), now(), zone) + " · " + kind.label()
                + " —— " + item.content();
    }

    /** 列表项的 JSON 形态（REST 与前端共用）。 */
    public Map<String, Object> toMap(ReminderItem item) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", item.id());
        body.put("kind", item.kind());
        body.put("kindLabel", ReminderKind.of(item.kind()).label());
        body.put("content", item.content());
        body.put("remindAt", Instant.ofEpochMilli(item.remindAt()).toString());
        body.put("remindAtText", ReminderTime.human(item.remindAt(), now(), zone));
        body.put("status", item.status());
        body.put("sessionId", item.sessionId());
        body.put("createdAt", Instant.ofEpochMilli(item.createdAt()).toString());
        body.put("due", ReminderStore.STATUS_DUE.equals(item.status()));
        return body;
    }
}
