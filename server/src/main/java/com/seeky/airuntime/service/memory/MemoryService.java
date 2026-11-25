package com.seeky.airuntime.service.memory;

import com.seeky.airuntime.dto.Mem0Result;
import com.seeky.airuntime.dto.MemoryItem;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 记忆业务层：把 mem0 的原子操作拼成「陪伴场景要的语义」。
 *
 * <p>核心只有一条规则 —— **同一个槽位以新为准**：
 * <pre>
 *   用户先说「我住北京」，后说「我搬到上海了」
 *   → 两条的 slot 都是 profile.city
 *   → 删掉旧条目、写入新条目（旧版本留在 mem0 的 history 里，记忆页仍能查到"改过什么"）
 * </pre>
 * 为什么不让 mem0 自己判断（{@code infer=true}）：它的 LLM 只看这一轮对话，不看我们的槽位体系，
 * 「搬到上海」很可能被它当成一条新记忆 ADD 进去，于是两条并存，模型答"你住北京"也说得通。
 * 槽位判断放在这里，行为才是确定的。
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    /** 单机 Demo 的默认用户；前端不传 userId 时用它。 */
    public static final String DEFAULT_USER_ID = "local-user";

    /** 列出上限：本地 mem0 没有分页，单次最多 1000 条，够这个 Demo 用。 */
    private static final int MAX_LIST = 1000;

    private final Mem0Client mem0;

    public MemoryService(Mem0Client mem0) {
        this.mem0 = mem0;
    }

    public String resolveUser(String userId) {
        return userId == null || userId.isBlank() ? DEFAULT_USER_ID : userId.trim();
    }

    /** 记忆页：全部记忆，按分类分组交给前端渲染。 */
    public Mono<Mem0Result<List<MemoryItem>>> overview(String userId, Integer limit) {
        int topK = limit == null || limit < 1 ? MAX_LIST : Math.min(limit, MAX_LIST);
        return mem0.listAll(resolveUser(userId), null, topK);
    }

    /** 语义召回（对话链路与调试接口共用）。 */
    public Mono<Mem0Result<List<MemoryItem>>> recall(String userId, String query, Integer topK) {
        return mem0.search(resolveUser(userId), query,
                topK == null || topK < 1 ? mem0.searchLimit() : topK, null);
    }

    /**
     * 写一条记忆，并保证同槽位以新为准。
     *
     * <p>三步：列出全部 → 找出同 slot 的旧条目 → 文本不同就删旧加新、相同就原地刷新。
     * 列表读取失败时**仍然写入**：宁可多一条待对账的记忆，也不能把用户刚说的话丢掉。
     */
    public Mono<Mem0Result<MemoryItem>> remember(String userId, String sessionId, String slot,
            String text, String category, Double importance) {
        String uid = resolveUser(userId);
        if (text == null || text.isBlank()) {
            return Mono.just(Mem0Result.failed("text 不能为空"));
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("slot", slot == null ? "" : slot.trim());
        metadata.put("category", category == null || category.isBlank() ? "other" : category.trim());
        metadata.put("importance", importance == null ? 0.5 : Math.max(0.0, Math.min(1.0, importance)));

        return mem0.listAll(uid, null, MAX_LIST)
                .flatMap(list -> {
                    if (list.degraded() || list.value() == null) {
                        log.warn("[memory] 读取旧记忆失败（{}），直接写入新条目", list.reason());
                        return mem0.add(uid, sessionId, text, metadata);
                    }
                    List<MemoryItem> sameSlot = list.value().stream()
                            .filter(item -> metadata.get("slot").equals(item.slot()))
                            .toList();
                    if (sameSlot.isEmpty()) {
                        return mem0.add(uid, sessionId, text, metadata);
                    }
                    // 文本已经一致：刷新一次（更新 importance/时间，history 里留下"再次确认"的痕迹）
                    if (sameSlot.size() == 1 && text.equals(sameSlot.get(0).memory())) {
                        return mem0.update(sameSlot.get(0).id(), text, metadata);
                    }
                    log.info("[memory] 槽位 {} 覆盖：{} 条旧记忆将被删除", metadata.get("slot"), sameSlot.size());
                    return Flux.fromIterable(sameSlot)
                            .flatMap(item -> mem0.delete(item.id()))
                            .then(mem0.add(uid, sessionId, text, metadata));
                })
                .onErrorResume(ex -> {
                    log.warn("[memory] 写入失败: {}", ex.getMessage());
                    return Mono.just(Mem0Result.failed(ex.getMessage()));
                });
    }

    public Mono<Mem0Result<Void>> forget(String userId, String id) {
        if (id == null || id.isBlank()) {
            return Mono.just(Mem0Result.failed("id 不能为空"));
        }
        return mem0.delete(id);
    }

    /** 这条记忆被改过几次（记忆页展开明细用）。 */
    public Mono<Mem0Result<List<MemoryItem>>> history(String id) {
        return mem0.history(id);
    }

    public Mono<Boolean> healthy() {
        return mem0.healthy();
    }

    /** 设置页展示用：记忆服务地址（不含 Key）。 */
    public String baseUrl() {
        return mem0.properties().baseUrl();
    }

    public String agentId() {
        return mem0.agentId();
    }
}
