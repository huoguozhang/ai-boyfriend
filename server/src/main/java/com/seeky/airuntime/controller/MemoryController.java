package com.seeky.airuntime.controller;

import com.seeky.airuntime.auth.AuthContext;
import com.seeky.airuntime.common.Result;
import com.seeky.airuntime.dto.MemoryItem;
import com.seeky.airuntime.dto.MemoryRequests;
import com.seeky.airuntime.service.memory.MemoryService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 记忆接口：对外的「她记得的事」。
 *
 * <p>路径与字段以前端（{@code front/src/components/MemoryPage.jsx}）为准 —— 那一页是产品语言，
 * 后端跟着它走，而不是反过来让前端改：
 * <ul>
 *   <li>{@code /fact/create} 收 {@code {text, category}}，没有槽位概念（手动加的不参与覆盖）；</li>
 *   <li>{@code /fact/delete} 收 {@code {factId}}；</li>
 *   <li>{@code /overview/get} 返回 {@code {total, groups:{profile:[…]}, followUps}}，
 *       每条记忆的正文叫 {@code text}。</li>
 * </ul>
 *
 * <p>mem0 不可达时**不报错**：返回 {@code degraded=true} 的空结构，前端显示"记忆服务暂不可用"，
 * 聊天本身照常 —— 记忆是增强，不是前置条件。
 */
@RestController
@RequestMapping("/api/v1/memory")
public class MemoryController {

    /** 前端的五个分区，顺序即展示顺序。 */
    private static final List<String> GROUP_KEYS = List.of("profile", "preference", "relation", "plan", "other");

    private final MemoryService memory;

    public MemoryController(MemoryService memory) {
        this.memory = memory;
    }

    /** 记忆页首屏：总数 + 分区 + 待回访（回访在第 6 步接上，现在先给空结构，前端不会白屏）。 */
    @PostMapping("/overview/get")
    public Mono<Result<Map<String, Object>>> overview(
            ServerWebExchange exchange,
            @RequestBody(required = false) MemoryRequests.Overview request) {
        MemoryRequests.Overview req = request == null
                ? new MemoryRequests.Overview(null, null) : request;
        // 记忆维度只认 token 里的 userId：请求体里的那个可以随便填，填成别人的就是越权读
        return memory.overview(AuthContext.requireUserId(exchange), req.limit()).map(result -> {
            List<MemoryItem> items = result.value() == null ? List.of() : result.value();
            Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
            GROUP_KEYS.forEach(key -> groups.put(key, new ArrayList<>()));
            for (MemoryItem item : items) {
                groups.computeIfAbsent(groupOf(item.category()), key -> new ArrayList<>()).add(toFact(item));
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("total", items.size());
            body.put("groups", groups);
            body.put("facts", items.stream().map(this::toFact).toList());
            body.put("followUps", Map.of("pending", List.of(), "asked", List.of()));
            body.put("degraded", result.degraded());
            body.put("reason", result.reason());
            body.put("engine", "mem0");
            return Result.ok(body);
        });
    }

    /** 手动添加一条：没有槽位，因此永远新增，不会覆盖她说过的其它事。 */
    @PostMapping("/fact/create")
    public Mono<Result<Map<String, Object>>> createFact(ServerWebExchange exchange,
            @RequestBody Map<String, Object> body) {
        String text = str(body.get("text"));
        if (text.isBlank()) {
            return Mono.just(Result.error(4202, "text 不能为空"));
        }
        String category = str(body.get("category"));
        if (category.isBlank()) {
            category = "other";
        }
        // 手动条目用独立槽位：用户手动补的是"额外一条"，不该把自动抽取的同槽位记忆顶掉
        String slot = "manual." + UUID.randomUUID().toString().substring(0, 8);
        return memory.remember(AuthContext.requireUserId(exchange), null, slot, text, category, 0.6)
                .map(result -> result.ok()
                        ? Result.ok(toFact(result.value()))
                        : Result.<Map<String, Object>>error(6006, "记忆写入失败：" + result.reason()));
    }

    /** 删掉一条：用户说"忘了这个"，必须真的从 mem0 删除，否则下次还会被召回。 */
    @PostMapping("/fact/delete")
    public Mono<Result<Boolean>> deleteFact(ServerWebExchange exchange,
            @RequestBody Map<String, Object> body) {
        String id = str(body.get("factId"));
        if (id.isBlank()) {
            id = str(body.get("id"));
        }
        if (id.isBlank()) {
            return Mono.just(Result.error(4202, "factId 不能为空"));
        }
        String finalId = id;
        return memory.forget(AuthContext.requireUserId(exchange), finalId)
                .map(result -> result.degraded()
                        ? Result.<Boolean>error(6006, "记忆服务不可用：" + result.reason())
                        : Result.ok(true));
    }

    /** 记忆引擎状态：设置页显示「mem0 是否就绪、存了多少条」。 */
    @PostMapping("/engine/get")
    public Mono<Result<Map<String, Object>>> engine(ServerWebExchange exchange) {
        String userId = AuthContext.requireUserId(exchange);
        return memory.healthy().flatMap(up -> memory.overview(userId, 1000).map(result -> {
            int count = result.value() == null ? 0 : result.value().size();
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("engine", "mem0 (local docker)");
            status.put("ready", up);
            status.put("count", count);
            status.put("error", up ? null : "连不上 mem0（确认 6101 的容器在运行）");
            status.put("baseUrl", memory.baseUrl());
            status.put("agentId", memory.agentId());
            return Result.ok(Map.<String, Object>of("status", status));
        }));
    }

    // ===== 以下是给调试/后续页面用的通用接口，与上面的前端专用接口并存 =====

    @PostMapping("/create")
    public Mono<Result<MemoryItem>> create(ServerWebExchange exchange,
            @RequestBody MemoryRequests.Create request) {
        if (request == null || request.text() == null || request.text().isBlank()) {
            return Mono.just(Result.error(4202, "text 不能为空"));
        }
        return memory.remember(AuthContext.requireUserId(exchange), null, request.slot(), request.text(),
                        request.category(), request.importance())
                .map(result -> result.ok()
                        ? Result.ok(result.value())
                        : Result.<MemoryItem>error(6006, "记忆写入失败：" + result.reason()));
    }

    @PostMapping("/delete")
    public Mono<Result<Boolean>> delete(ServerWebExchange exchange,
            @RequestBody MemoryRequests.Delete request) {
        if (request == null || request.id() == null || request.id().isBlank()) {
            return Mono.just(Result.error(4202, "id 不能为空"));
        }
        return memory.forget(AuthContext.requireUserId(exchange), request.id())
                .map(result -> result.degraded()
                        ? Result.<Boolean>error(6006, "记忆服务不可用：" + result.reason())
                        : Result.ok(true));
    }

    /** 召回预览：这句话会命中哪些记忆（排查"她为什么突然提起某事"）。 */
    @PostMapping("/search")
    public Mono<Result<Map<String, Object>>> search(ServerWebExchange exchange,
            @RequestBody MemoryRequests.Search request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            return Mono.just(Result.error(4202, "query 不能为空"));
        }
        return memory.recall(AuthContext.requireUserId(exchange), request.query(), request.topK())
                .map(result -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("items", result.value() == null ? List.of() : result.value());
            body.put("degraded", result.degraded());
            body.put("reason", result.reason());
            return Result.ok(body);
        });
    }

    /** 某条记忆被改过几次。 */
    @PostMapping("/history/get")
    public Mono<Result<Map<String, Object>>> history(@RequestBody MemoryRequests.Delete request) {
        if (request == null || request.id() == null || request.id().isBlank()) {
            return Mono.just(Result.error(4202, "id 不能为空"));
        }
        return memory.history(request.id()).map(result -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("items", result.value() == null ? List.of() : result.value());
            body.put("degraded", result.degraded());
            return Result.ok(body);
        });
    }

    /** mem0 存的 category（我们自己的六类）→ 前端的分区键。 */
    private String groupOf(String category) {
        return switch (category == null ? "" : category) {
            case "profile" -> "profile";
            case "preference", "habit" -> "preference";
            case "relationship" -> "relation";
            case "event" -> "plan";
            default -> "other";
        };
    }

    private Map<String, Object> toFact(MemoryItem item) {
        Map<String, Object> fact = new LinkedHashMap<>();
        fact.put("id", item.id());
        fact.put("text", item.memory());
        fact.put("slot", item.slot());
        fact.put("category", item.category());
        fact.put("importance", item.metadata() == null ? null : item.metadata().get("importance"));
        fact.put("createdAt", item.createdAt());
        fact.put("updatedAt", item.updatedAt());
        return fact;
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
