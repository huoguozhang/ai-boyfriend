package com.seeky.airuntime.service.chat.nodes.common;

import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * 把一段文本包成框架认识的流块。
 *
 * <p>为什么不直接给 {@code Flux<String>}：框架从 {@code GraphFlux} 里取正文时只认
 * {@code ChatResponse}（Spring AI 的原生类型），塞 String 进去 {@code chunk()} 会恒为空 ——
 * 用户侧表现就是"一个字都没有"，而且不报错，非常难查。
 */
public final class GraphChunks {

    private GraphChunks() {
    }

    public static ChatResponse chunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
