package com.seeky.airuntime.service.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 管道内的"指令帧"：工具节点把要做的事写成一 {@code {"event":..,"data":..}} 交给 Service。
 *
 * <p>为什么用这套私有协议：图节点之间只能靠"流块"往外推东西，而工具要推的不是文字，
 * 是"发一张图""刷新记忆页"这类动作。要是给框架加新节点类型，代价远大于在块上打个标记；
 * 只要解析口子限定在 tool 节点的块上，正文永远不会被误解析。
 */
public record ToolFrame(String event, Map<String, Object> data) {

    private static final Logger log = LoggerFactory.getLogger(ToolFrame.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    /** 解析一块；不是合法指令帧就返回空（宁可丢一帧，也不能中断整轮回复）。 */
    public static Optional<ToolFrame> parse(String chunk) {
        if (chunk == null || chunk.isBlank() || chunk.charAt(0) != '{') {
            return Optional.empty();
        }
        try {
            JsonNode root = JSON.readTree(chunk);
            String event = root.path("event").asText("");
            if (event.isBlank()) {
                return Optional.empty();
            }
            Map<String, Object> data = root.hasNonNull("data")
                    ? JSON.convertValue(root.get("data"), MAP) : Map.of();
            return Optional.of(new ToolFrame(event, data));
        } catch (Exception ex) {
            log.warn("[chat] 工具帧解析失败: {}", chunk);
            return Optional.empty();
        }
    }
}
