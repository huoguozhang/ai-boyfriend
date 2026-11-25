package com.seeky.airuntime.service.chat.nodes.common;

import com.alibaba.cloud.ai.graph.OverAllState;
import java.util.List;
import java.util.Map;

/** 把状态里的历史消息拼成提示词里能看懂的文本。 */
public final class HistoryText {

    private HistoryText() {
    }

    /**
     * 输出形如：
     * <pre>
     * 他：今天加班到十点
     * 她：这么晚，吃饭了没
     * </pre>
     * 用「他/她」而不是 user/assistant：改写与抽取的提示词都是中文语境，混英文标签会让模型
     * 在输出里也带上 role 字样。
     */
    public static String of(OverAllState state, int maxMessages) {
        List<Map<String, String>> history = state.value(StateKeys.HISTORY, List.of());
        if (history == null || history.isEmpty()) {
            return "（这是你们这次聊天的第一句）";
        }
        int from = Math.max(0, history.size() - maxMessages);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < history.size(); i += 1) {
            Map<String, String> message = history.get(i);
            String role = "assistant".equals(message.get("role")) ? "她" : "他";
            sb.append(role).append("：").append(message.getOrDefault("content", "")).append('\n');
        }
        return sb.toString().trim();
    }
}
