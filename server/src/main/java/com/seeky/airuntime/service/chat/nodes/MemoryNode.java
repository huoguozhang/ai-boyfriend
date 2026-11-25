package com.seeky.airuntime.service.chat.nodes;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.seeky.airuntime.config.Mem0Properties;
import com.seeky.airuntime.dto.MemoryItem;
import com.seeky.airuntime.service.chat.nodes.common.GraphNode;
import com.seeky.airuntime.service.chat.nodes.common.StateKeys;
import com.seeky.airuntime.service.memory.MemoryService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 记忆召回：拿改写后的 query 去 mem0 做语义检索，拼成注入 system 的文本块。
 *
 * <p>两个约束：
 * <ul>
 *   <li>**按字符上限截断**（{@code MEM0_MAX_CONTEXT_CHARS}）：记忆一多，system 提示词会把
 *       角色设定挤掉，她就变成"记得很多事但没有性格"的问答机；</li>
 *   <li>**失败就当没有记忆**：mem0 挂了、超时了，返回空块，对话照常走。</li>
 * </ul>
 */
@Component
public class MemoryNode implements GraphNode {

    private static final Logger log = LoggerFactory.getLogger(MemoryNode.class);

    private final MemoryService memory;
    private final Mem0Properties properties;

    public MemoryNode(MemoryService memory, Mem0Properties properties) {
        this.memory = memory;
        this.properties = properties;
    }

    @Override
    public String id() {
        return "memory";
    }

    @Override
    public String name() {
        return "memory_recall";
    }

    @Override
    public String label() {
        return "记忆召回";
    }

    @Override
    public String type() {
        return "agent";
    }

    @Override
    public String role() {
        return "retriever";
    }

    @Override
    public String description() {
        return "读 mem0 语义记忆，超限截断，失败降级为空";
    }

    @Override
    public List<String> inputKeys() {
        return List.of(StateKeys.REWRITTEN, StateKeys.USER_ID);
    }

    @Override
    public String outputKey() {
        return StateKeys.MEMORY;
    }

    @Override
    public Flux<String> stream(OverAllState state) {
        String userId = state.value(StateKeys.USER_ID, MemoryService.DEFAULT_USER_ID);
        String query = state.value(StateKeys.REWRITTEN, "");
        if (!properties.enabled() || query == null || query.isBlank()) {
            return Flux.just("");
        }
        int limit = properties.searchLimitOrDefault();
        int maxChars = properties.maxContextCharsOrDefault();

        return memory.recall(userId, query, limit)
                .map(result -> {
                    if (result.degraded()) {
                        log.warn("[memory] 召回降级: {}", result.reason());
                        return "";
                    }
                    List<MemoryItem> items = result.value();
                    if (items == null || items.isEmpty()) {
                        return "";
                    }
                    StringBuilder sb = new StringBuilder();
                    for (MemoryItem item : items) {
                        if (item.memory() == null || item.memory().isBlank()) {
                            continue;
                        }
                        String line = "- " + item.memory().trim();
                        if (sb.length() + line.length() + 1 > maxChars) {
                            break;
                        }
                        sb.append(line).append('\n');
                    }
                    return sb.toString().trim();
                })
                .flux();
    }
}
