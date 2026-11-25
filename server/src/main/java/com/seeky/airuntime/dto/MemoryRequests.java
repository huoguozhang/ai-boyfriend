package com.seeky.airuntime.dto;

/**
 * 记忆接口的请求体（四个端点共用一组轻量 record）。
 *
 * <p>{@code userId} 留空时统一落到 {@code local-user}：这是单机 Demo，
 * 前端不必关心用户体系，但后端仍保留这一维度，将来接登录只改取值来源。
 */
public final class MemoryRequests {

    private MemoryRequests() {
    }

    /** 首屏/记忆页：列出全部记忆。 */
    public record Overview(String userId, Integer limit) {
    }

    /** 手动添加一条记忆（用户自己在设置页写的那种）。 */
    public record Create(String userId, String text, String slot, String category, Double importance) {
    }

    public record Delete(String userId, String id) {
    }

    /** 调试/召回预览：这句话会命中哪些记忆。 */
    public record Search(String userId, String query, Integer topK) {
    }
}
