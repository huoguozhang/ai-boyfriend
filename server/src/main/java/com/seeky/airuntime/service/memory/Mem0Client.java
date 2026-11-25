package com.seeky.airuntime.service.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seeky.airuntime.config.Mem0Properties;
import com.seeky.airuntime.dto.Mem0Result;
import com.seeky.airuntime.dto.MemoryItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

/**
 * 本地 mem0 记忆服务客户端。
 *
 * <p>是 ai-runtime {@code Mem0Service} 的同构实现：同样的路径（{@code /memories}、{@code /search}、
 * {@code /memories/{id}/history}）、同样的身份维度（{@code user_id} / {@code run_id} / {@code agent_id}）、
 * 同样的 {@code X-API-Key} 头、同样的 outcome 归类（ok / no-hit / failed / skipped）。
 * 唯一的差别是**实例选择**：那边走 Nacos 服务发现，这里直接读 {@code .env} 的 {@code MEM0_BASE_URL}。
 *
 * <p>三条不能破的约定：
 * <ol>
 *   <li>写入固定 {@code infer=false} —— 抽取由 {@link MemoryExtractor} 用自己的 prompt 做，
 *       mem0 只负责存与检索（本地容器里也没有 LLM）。</li>
 *   <li>解析失败必须归 {@code failed}，不能当"没有记忆"，否则调用方会重复写入。</li>
 *   <li>任何异常都降级成 {@code failed/skipped} 返回空，绝不把错误抛给对话链路 ——
 *       记忆挂了只是"她暂时想不起来"，不能变成"她不能说话"。</li>
 * </ol>
 */
@Service
public class Mem0Client {

    private static final Logger log = LoggerFactory.getLogger(Mem0Client.class);

    static final String PATH_MEMORIES = "/memories";
    static final String PATH_SEARCH = "/search";
    static final String API_KEY_HEADER = "X-API-Key";
    /** 本地 mem0 没有分页，只认 top_k，且有上限。 */
    static final int MAX_TOP_K = 1000;

    private final Mem0Properties properties;
    private final WebClient webClient;
    /**
     * 独立持有的 Jackson 2 mapper：Spring Boot 4 的容器里是 Jackson 3（{@code tools.jackson}），
     * 把 Jackson 2 的 ObjectMapper 当 bean 注入会直接起不来。这里只解析自己这一份 mem0 响应，
     * 不参与 HTTP 序列化，也就不需要跟框架的 ObjectMapper 配置保持一致。
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Mem0Client(Mem0Properties properties) {
        this.properties = properties;
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE);
        if (!properties.apiKey().isBlank()) {
            builder.defaultHeader(API_KEY_HEADER, properties.apiKey());
        }
        this.webClient = builder.build();
    }

    public boolean configured() {
        return properties.configured();
    }

    public String agentId() {
        return properties.agentId();
    }

    public int searchLimit() {
        return properties.searchLimitOrDefault();
    }

    public Mem0Properties properties() {
        return properties;
    }

    // ------------------------------------------------------------------ 读

    /**
     * 语义检索。{@code filters} 只用于收窄候选（比如按 metadata 的 category 过滤），
     * 业务层的二次筛选仍由调用方做。
     */
    public Mono<Mem0Result<List<MemoryItem>>> search(String userId, String query, int topK,
            Map<String, Object> filters) {
        if (userId == null || userId.isBlank() || !configured()) {
            return Mono.just(Mem0Result.skipped());
        }
        if (query == null || query.isBlank()) {
            return listAll(userId, null, topK);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("user_id", userId);
        body.put("agent_id", properties.agentId());
        body.put("top_k", clamp(topK));
        if (filters != null && !filters.isEmpty()) {
            body.put("filters", filters);
        }
        return webClient.post()
                .uri(PATH_SEARCH)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                        .map(raw -> new Mem0HttpException(resp.statusCode().value(), raw)))
                .bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(raw -> itemsResult(raw, "search"))
                .timeout(properties.responseTimeout())
                .onErrorResume(ex -> Mono.just(failed("search", ex)));
    }

    /** 列出记忆（不做语义检索）：首屏与记忆页用。 */
    public Mono<Mem0Result<List<MemoryItem>>> listAll(String userId, String runId, int maxItems) {
        if (userId == null || userId.isBlank() || !configured()) {
            return Mono.just(Mem0Result.skipped());
        }
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(properties.baseUrl() + PATH_MEMORIES)
                .queryParam("user_id", userId)
                .queryParam("agent_id", properties.agentId())
                .queryParam("top_k", clamp(maxItems));
        if (runId != null && !runId.isBlank()) {
            builder.queryParam("run_id", runId);
        }
        return webClient.get()
                .uri(builder.build(true).toUriString())
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                        .map(raw -> new Mem0HttpException(resp.statusCode().value(), raw)))
                .bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(raw -> itemsResult(raw, "list"))
                .timeout(properties.responseTimeout())
                .onErrorResume(ex -> Mono.just(failed("list", ex)));
    }

    public Mono<Mem0Result<List<MemoryItem>>> history(String id) {
        if (id == null || id.isBlank() || !configured()) {
            return Mono.just(Mem0Result.skipped());
        }
        return webClient.get()
                .uri(PATH_MEMORIES + "/" + id + "/history")
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                        .map(raw -> new Mem0HttpException(resp.statusCode().value(), raw)))
                .bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(raw -> itemsResult(raw, "history"))
                .timeout(properties.responseTimeout())
                .onErrorResume(ex -> Mono.just(failed("history", ex)));
    }

    // ------------------------------------------------------------------ 写

    /**
     * 写入一条记忆。{@code infer} 没有参数：固定 false（见类注释）。
     *
     * @param userId   用户维度
     * @param sessionId 会话维度，写进 mem0 的 {@code run_id}
     * @param text     已由上游整理好的记忆正文
     * @param metadata 槽位信息（slot / category / importance）
     */
    public Mono<Mem0Result<MemoryItem>> add(String userId, String sessionId, String text,
            Map<String, Object> metadata) {
        if (userId == null || userId.isBlank() || text == null || text.isBlank() || !configured()) {
            return Mono.just(Mem0Result.skipped());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", List.of(Map.of("role", "user", "content", text)));
        body.put("user_id", userId);
        if (sessionId != null && !sessionId.isBlank()) {
            body.put("run_id", sessionId);
        }
        body.put("agent_id", properties.agentId());
        if (metadata != null && !metadata.isEmpty()) {
            body.put("metadata", metadata);
        }
        body.put("infer", false);
        return webClient.post()
                .uri(PATH_MEMORIES)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                        .map(raw -> new Mem0HttpException(resp.statusCode().value(), raw)))
                .bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(raw -> addResult(raw, metadata))
                .timeout(properties.responseTimeout())
                .onErrorResume(ex -> Mono.just(failed("add", ex)));
    }

    /**
     * 写入的响应解析：mem0 的 ADD 响应只回 {@code id / memory / event}，不带 metadata 与时间，
     * 直接用会把「刚写进去的槽位」显示成空对象。元数据本来就是入参，这里补回去。
     */
    private Mem0Result<MemoryItem> addResult(String raw, Map<String, Object> metadata) {
        Mem0Result<MemoryItem> parsed = itemResult(raw, "add");
        if (!parsed.ok() || parsed.value() == null) {
            return parsed;
        }
        MemoryItem item = parsed.value();
        Map<String, Object> merged = item.metadata() == null || item.metadata().isEmpty()
                ? (metadata == null ? Map.of() : metadata)
                : item.metadata();
        return Mem0Result.ok(new MemoryItem(item.id(), item.memory(), merged,
                item.score(), item.createdAt(), item.updatedAt()));
    }

    public Mono<Mem0Result<MemoryItem>> update(String id, String text, Map<String, Object> metadata) {
        if (id == null || id.isBlank() || !configured()) {
            return Mono.just(Mem0Result.skipped());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (text != null) {
            body.put("text", text);
        }
        if (metadata != null && !metadata.isEmpty()) {
            body.put("metadata", metadata);
        }
        return webClient.put()
                .uri(PATH_MEMORIES + "/" + id)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                        .map(raw -> new Mem0HttpException(resp.statusCode().value(), raw)))
                .bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(raw -> updatedResult(raw, id, text, metadata))
                .timeout(properties.responseTimeout())
                .onErrorResume(ex -> Mono.just(failed("update", ex)));
    }

    /**
     * 更新的响应解析。
     *
     * <p>mem0 2.x 的 PUT 成功时只回 {@code {"message":"Memory updated successfully!"}}，
     * 没有条目字段——直接用 {@link #itemResult} 会判成 failed，上层就会以为没改成。
     * 所以这里退一步：HTTP 已 2xx、且响应里明确写了 success，就用入参拼一个最小条目返回。
     */
    private Mem0Result<MemoryItem> updatedResult(String raw, String id, String text,
            Map<String, Object> metadata) {
        Mem0Result<MemoryItem> parsed = itemResult(raw, "update");
        if (parsed.ok()) {
            return parsed;
        }
        if (raw != null && raw.toLowerCase().contains("success")) {
            return Mem0Result.ok(new MemoryItem(id, text,
                    metadata == null ? Map.of() : metadata, null, null, null));
        }
        return parsed;
    }

    public Mono<Mem0Result<Void>> delete(String id) {
        if (id == null || id.isBlank() || !configured()) {
            return Mono.just(Mem0Result.skipped());
        }
        return webClient.delete()
                .uri(PATH_MEMORIES + "/" + id)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                        .map(raw -> new Mem0HttpException(resp.statusCode().value(), raw)))
                .bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(ignored -> Mem0Result.<Void>ok(null))
                .timeout(properties.responseTimeout())
                .onErrorResume(ex -> Mono.just(Mem0Result.failed(reason(ex))));
    }

    /** 探活：给健康检查与设置页用，不抛异常。 */
    public Mono<Boolean> healthy() {
        if (!configured()) {
            return Mono.just(false);
        }
        return webClient.get()
                .uri("/health")
                .retrieve()
                .bodyToMono(String.class)
                .map(body -> body != null && body.contains("\"ok\""))
                .timeout(properties.responseTimeout())
                .onErrorResume(ex -> Mono.just(false));
    }

    // ------------------------------------------------------------------ 解析

    /**
     * 列表类响应：空数组是"真的没有"（no-hit），解析失败是 failed —— 两者不能混。
     * 兼容 {@code {results:[…]}} / {@code {memories:[…]}} / 裸数组 / 单对象四种形态。
     */
    Mem0Result<List<MemoryItem>> itemsResult(String raw, String op) {
        JsonNode root = parseRoot(raw, op);
        if (root == null) {
            return Mem0Result.failed("invalid-response");
        }
        List<MemoryItem> items = parseItems(root);
        if (items.isEmpty()) {
            log.debug("[mem0] {} 无记忆, bytes={}", op, raw.length());
            return Mem0Result.noHit(List.of());
        }
        log.debug("[mem0] {} 命中 {} 条", op, items.size());
        return Mem0Result.ok(items);
    }

    /** 单条写入：拿不到 id 就算失败，调用方不能把 null 当落库成功。 */
    Mem0Result<MemoryItem> itemResult(String raw, String op) {
        JsonNode root = parseRoot(raw, op);
        if (root == null) {
            return Mem0Result.failed("invalid-response");
        }
        List<MemoryItem> items = parseItems(root);
        if (items.isEmpty()) {
            log.warn("[mem0] {} 响应不含记忆条目, bytes={}", op, raw.length());
            return Mem0Result.failed("empty-item");
        }
        return Mem0Result.ok(items.get(0));
    }

    private JsonNode parseRoot(String raw, String op) {
        if (raw == null || raw.isBlank()) {
            log.warn("[mem0] {} 响应为空", op);
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            return root == null || root.isMissingNode() ? null : root;
        } catch (Exception ex) {
            log.warn("[mem0] {} 响应不是合法 JSON, bytes={}", op, raw.length());
            return null;
        }
    }

    List<MemoryItem> parseItems(JsonNode root) {
        JsonNode array = root;
        if (!array.isArray()) {
            array = root.path("results");
            if (!array.isArray()) {
                array = root.path("memories");
            }
            if (!array.isArray()) {
                MemoryItem single = parseItem(root);
                return single == null ? List.of() : List.of(single);
            }
        }
        List<MemoryItem> items = new ArrayList<>(array.size());
        for (JsonNode node : array) {
            MemoryItem item = parseItem(node);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    private MemoryItem parseItem(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String id = text(node, "id");
        String memory = text(node, "memory");
        if (memory == null) {
            memory = text(node, "text");
        }
        if (id == null && memory == null) {
            return null;
        }
        Map<String, Object> metadata = parseMetadata(node.get("metadata"));
        Double score = node.path("score").isNumber() ? node.path("score").asDouble() : null;
        return new MemoryItem(id, memory, metadata, score,
                text(node, "created_at"), text(node, "updated_at"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadata(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Map.of();
        }
        if (node.isTextual()) {
            try {
                return parseMetadata(objectMapper.readTree(node.asText()));
            } catch (Exception ex) {
                return Map.of();
            }
        }
        if (!node.isObject()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, Map.class);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private int clamp(int topK) {
        if (topK < 1) {
            return properties.searchLimitOrDefault();
        }
        return Math.min(topK, MAX_TOP_K);
    }

    private <T> Mem0Result<T> failed(String op, Throwable ex) {
        log.warn("[mem0] {} 失败（降级为空）: {}", op, reason(ex));
        return Mem0Result.failed(reason(ex));
    }

    private String reason(Throwable ex) {
        if (ex instanceof Mem0HttpException http) {
            return "http-" + http.status;
        }
        if (ex instanceof java.util.concurrent.TimeoutException) {
            return "timeout";
        }
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    /** 带上状态码的异常，方便把 4xx/5xx 归类进日志（不含响应体，避免把记忆内容写进日志）。 */
    static final class Mem0HttpException extends RuntimeException {
        private final int status;

        Mem0HttpException(int status, String rawBody) {
            super("mem0 http " + status + (rawBody == null || rawBody.isBlank() ? "" : "（响应已忽略）"));
            this.status = status;
        }
    }
}
