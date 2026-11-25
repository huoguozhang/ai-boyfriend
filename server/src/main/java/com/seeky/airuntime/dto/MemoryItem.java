package com.seeky.airuntime.dto;

import java.util.Map;

/**
 * 一条记忆，字段与 mem0 返回对齐（与 ai-runtime 的 {@code MemoryItem} 同形）。
 *
 * <p>{@code memory} 是正文；{@code metadata} 里放我们自己的槽位信息
 * （{@code slot} / {@code category} / {@code importance}），用于「同槽位以新为准」的判断；
 * {@code score} 只在语义检索时有值。
 *
 * @param id        mem0 条目 id（更新/删除都要用它）
 * @param memory    记忆正文
 * @param metadata  元数据（槽位、分类、重要度等）
 * @param score     检索相似度，列表接口为 null
 * @param createdAt 创建时间（mem0 原样字符串，可能带时区）
 * @param updatedAt 更新时间
 */
public record MemoryItem(
        String id,
        String memory,
        Map<String, Object> metadata,
        Double score,
        String createdAt,
        String updatedAt
) {

    /** metadata 里的槽位，没有就返回空串（旧数据/手动添加的可能没有）。 */
    public String slot() {
        Object value = metadata == null ? null : metadata.get("slot");
        return value == null ? "" : String.valueOf(value);
    }

    public String category() {
        Object value = metadata == null ? null : metadata.get("category");
        return value == null ? "other" : String.valueOf(value);
    }
}
