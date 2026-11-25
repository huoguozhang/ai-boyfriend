package com.seeky.airuntime.controller;

import com.seeky.airuntime.auth.AuthContext;
import com.seeky.airuntime.common.BusinessException;
import com.seeky.airuntime.common.ErrorCode;
import com.seeky.airuntime.common.Result;
import com.seeky.airuntime.service.reminder.ReminderService;
import com.seeky.airuntime.store.ReminderItem;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

/**
 * 待办提醒接口（{@code /api/v1/reminders}）。
 *
 * <p>对话里说的提醒是<b>同一张表</b>里的同一批数据，这里只是另一个入口：
 * 列表用来让用户看见"她到底记住了什么"，{@code /due} 是到点投递的队列，
 * 前端拉到就弹一张提醒卡，用户点「知道了」再回调 {@code /ack}。
 *
 * <p>为什么用轮询而不是 WebSocket：这个 Demo 的运行形态是手机浏览器偶尔打开一下，
 * 为一条提醒维护长连接、还要处理断线重连，复杂度远超收益；轮询一次只查一条 SQL，
 * 而且"没打开的页面收不到提醒"本来就是可以接受的语义 —— 他回来时才看到，正是提醒该有的样子。
 *
 * <p>所有接口的身份都只来自 token，路径里的 id 也会再带一次 {@code user_id} 校验：
 * 拿到别人的提醒 id 也只会得到"没有这条"。
 */
@RestController
@RequestMapping("/api/v1/reminders")
public class ReminderController {

    /** 一次最多回多少条：提醒不该是瀑布流，够他看完最近的就够。 */
    private static final int LIST_LIMIT = 50;

    private final ReminderService reminders;

    public ReminderController(ReminderService reminders) {
        this.reminders = reminders;
    }

    /** 还没兑现的（含已到点未确认的），时间正序。 */
    @GetMapping
    public Result<List<Map<String, Object>>> list(ServerWebExchange exchange) {
        String userId = AuthContext.requireUserId(exchange);
        return Result.ok(reminders.upcoming(userId, LIST_LIMIT).stream()
                .map(reminders::toMap)
                .toList());
    }

    /** 待投递队列：到点了、还没送到他眼前的。 */
    @GetMapping("/due")
    public Result<List<Map<String, Object>>> due(ServerWebExchange exchange) {
        String userId = AuthContext.requireUserId(exchange);
        return Result.ok(reminders.due(userId).stream()
                .map(reminders::toMap)
                .toList());
    }

    /**
     * 他看到了这条提醒。
     *
     * <p>重复确认返回 ok=false 而不是报错：前端网络抖一下重发一次很正常，
     * 那条记录本来就已经 delivered，不该因此弹一个红条。
     */
    @PostMapping("/{id}/ack")
    public Result<Map<String, Object>> ack(ServerWebExchange exchange, @PathVariable String id) {
        String userId = AuthContext.requireUserId(exchange);
        boolean changed = reminders.ack(userId, id);
        return Result.ok(Map.of("id", id, "acknowledged", changed));
    }

    /** 取消一条提醒。没找到就明说，不返回"取消成功"。 */
    @PostMapping("/{id}/cancel")
    public Result<Map<String, Object>> cancel(ServerWebExchange exchange, @PathVariable String id) {
        String userId = AuthContext.requireUserId(exchange);
        ReminderItem item = reminders.cancelById(userId, id);
        if (item == null) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "没有这条提醒，或者它已经不在待办里了");
        }
        return Result.ok(reminders.toMap(item));
    }

    /**
     * 手动建一条（不经过对话）。
     *
     * <p>存在的理由有两个：设置页/调试需要一条确定的提醒；测试到期投递时不可能等三天。
     * 走的是和工具完全相同的 {@link ReminderService#create}，时间口径不会出现两套。
     */
    @PostMapping("/create")
    public Result<Map<String, Object>> create(ServerWebExchange exchange,
            @RequestBody(required = false) Map<String, Object> body) {
        String userId = AuthContext.requireUserId(exchange);
        Map<String, Object> input = body == null ? Map.of() : body;
        String content = String.valueOf(input.getOrDefault("content", ""));
        String kind = String.valueOf(input.getOrDefault("kind", "other"));
        String remindAt = String.valueOf(input.getOrDefault("remindAt", ""));
        String remindIn = String.valueOf(input.getOrDefault("remindIn", ""));
        ReminderService.Created created;
        try {
            created = reminders.create(userId, String.valueOf(input.getOrDefault("sessionId", "")),
                    kind, content, remindAt, remindIn);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_FAILED, ex.getMessage());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", created.id());
        result.put("kind", created.kind().code());
        result.put("kindLabel", created.kind().label());
        result.put("content", created.content());
        result.put("remindAt", created.remindAt());
        result.put("remindAtText", created.humanText());
        return Result.ok(result);
    }
}
