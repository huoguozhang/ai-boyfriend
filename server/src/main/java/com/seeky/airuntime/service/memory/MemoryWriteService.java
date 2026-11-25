package com.seeky.airuntime.service.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seeky.airuntime.config.EffectiveSettings;
import com.seeky.airuntime.dto.MemoryItem;
import com.seeky.airuntime.prompt.PromptCatalog;
import com.seeky.airuntime.prompt.PromptKeys;
import com.seeky.airuntime.service.chat.LlmClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 记忆写入：一轮对话结束后，异步判断"这轮有没有值得长期记住的事"，有就写进 mem0。
 *
 * <p>为什么异步、为什么不等结果：抽取要再调一次模型（1~4 秒），把它放进 SSE 链路里，
 * 用户会看到回复说完了但界面还在转圈。所以这里 fire-and-forget —— 抽到了就写，
 * 写失败只记日志，用户完全无感。
 *
 * <p>为什么不用 mem0 自己的 {@code infer=true}：那样抽取规则在 mem0 里，槽位体系（谁覆盖谁）
 * 就管不住了。这里用 {@code memory.extract.system} 抽出 {@code {slot, text, category}}，
 * 再由 {@link MemoryService#remember} 保证同槽位以新为准。
 *
 * <p>串行写：同一轮可能抽出两三条，用 {@code concatMap} 而不是 {@code flatMap}——
 * 并发写同一个槽位会让"哪条是新的"取决于时序。
 */
@Service
public class MemoryWriteService {

    private static final Logger log = LoggerFactory.getLogger(MemoryWriteService.class);
    /** 一轮对话里最多记 3 条：再多说明抽取规则太宽，会把闲聊也当事实。 */
    private static final int MAX_FACTS_PER_TURN = 3;

    private final LlmClient llm;
    private final PromptCatalog prompts;
    private final MemoryService memory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MemoryWriteService(LlmClient llm, PromptCatalog prompts, MemoryService memory) {
        this.llm = llm;
        this.prompts = prompts;
        this.memory = memory;
    }

    /**
     * 异步抽取并写入。返回发出的 Mono 只为便于测试，生产路径直接 subscribe。
     *
     * @param userText      用户这轮说的话
     * @param assistantText 她这轮的回复
     */
    public Mono<Integer> extractAndSave(String userId, String sessionId, String userText,
            String assistantText, EffectiveSettings settings) {
        if (!llm.configured(settings) || userText == null || userText.isBlank()) {
            return Mono.just(0);
        }
        String conversation = "他：" + userText.trim() + "\n她：" + (assistantText == null ? "" : assistantText.trim());

        return Mono.fromCallable(() -> prompts.render(PromptKeys.MEMORY_EXTRACT_SYSTEM,
                        Map.of("conversation", conversation)))
                .flatMap(system -> llm.complete(LlmClient.messages(system, null, conversation),
                        0.0, settings))
                .flatMapMany(raw -> Flux.fromIterable(parse(raw)))
                .take(MAX_FACTS_PER_TURN)
                .concatMap(fact -> memory.remember(userId, sessionId, fact.slot(), fact.text(),
                                fact.category(), fact.importance())
                        .doOnNext(result -> {
                            if (result.ok()) {
                                log.info("[memory] 记住: slot={} text={}", fact.slot(), fact.text());
                            } else {
                                log.warn("[memory] 未写入: slot={} reason={}", fact.slot(), result.reason());
                            }
                        })
                        .thenReturn(1))
                .reduce(0, Integer::sum)
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 抽到的一条事实。 */
    private record Fact(String slot, String text, String category, double importance) {
    }

    /** 模型可能带 ```json 包裹，取第一个 [ 到最后一个 ]。 */
    private List<Fact> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return List.of();
        }
        List<Fact> facts = new ArrayList<>();
        try {
            JsonNode array = objectMapper.readTree(raw.substring(start, end + 1));
            for (JsonNode node : array) {
                String text = node.path("text").asText("").trim();
                String slot = node.path("slot").asText("").trim();
                if (text.isBlank() || slot.isBlank()) {
                    continue;
                }
                String category = node.path("category").asText("other").trim();
                double importance = node.path("importance").isNumber()
                        ? node.path("importance").asDouble() : 0.5;
                facts.add(new Fact(slot, text, category, importance));
            }
        } catch (Exception ex) {
            log.debug("[memory] 抽取结果解析失败: {}", raw);
        }
        return facts;
    }

    /** 供记忆页展示"这条被改过几次"。 */
    public Mono<List<MemoryItem>> history(String id) {
        return memory.history(id).map(result -> result.value() == null ? List.of() : result.value());
    }
}
