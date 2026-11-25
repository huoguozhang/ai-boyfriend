package com.seeky.airuntime.service.chat.nodes;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ToolNode#looksTimed} 只负责"这句话里有没有时间线索"，用来决定要不要强制重试工具调用。
 *
 * <p>宁可漏判、不能误判：漏判最多走模型自己的判断，误判成"有时间"就可能替用户编一个时刻。
 */
class ToolNodeLooksTimedTest {

    @Test
    @DisplayName("数字 + 单位 / 点钟 都算有时间")
    void detectsTime() {
        assertThat(ToolNode.looksTimed("三天后提醒我交房租")).isTrue();
        assertThat(ToolNode.looksTimed("30分钟后叫我")).isTrue();
        assertThat(ToolNode.looksTimed("明天八点提醒我")).isTrue();
        assertThat(ToolNode.looksTimed("下午3点提醒我开会")).isTrue();
    }

    @Test
    @DisplayName("没有时间线索的不算")
    void missesNoTime() {
        assertThat(ToolNode.looksTimed("提醒我吃药")).isFalse();
        assertThat(ToolNode.looksTimed("别忘了多喝水")).isFalse();
    }
}
