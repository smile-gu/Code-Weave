package com.codingreview.controlplane.workflow.coding.stage;

import com.codingreview.controlplane.observability.run.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 验证生命周期合法转换与不确定写入后的封闭行为。 */
class CodingStageLifecycleTest {
    /** 活动阶段不能并行开始或跳过，结束之后不能重复改写。 */
    @Test void enforcesOneActiveStageAndSingleEnding() {
        var journal = mock(RunEventJournal.class);
        var lifecycle = new CodingStageLifecycle("turn", journal, new ObjectMapper());
        lifecycle.start(CodingStage.WORKSPACE);
        assertThatThrownBy(() -> lifecycle.start(CodingStage.CODING)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> lifecycle.skipRemaining("NO_CHANGES", "跳过", null)).isInstanceOf(IllegalStateException.class);
        lifecycle.complete("通过");
        assertThatThrownBy(() -> lifecycle.start(CodingStage.WORKSPACE)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> lifecycle.complete("重复")).isInstanceOf(IllegalStateException.class);
        lifecycle.skipRemaining("TURN_NOT_STARTED", "未执行", null);
        lifecycle.skipRemaining("TURN_NOT_STARTED", "重复调用", null);
        verify(journal, times(6)).append(anyString(), anyString(), eq(1), any(), any());
    }

    /** 写后抛错也不得重试同一事件或追加猜测性失败事实。 */
    @Test void closesLifecycleAfterUncertainWrite() {
        var journal = mock(RunEventJournal.class);
        when(journal.append(anyString(), anyString(), anyInt(), any(), any())).thenThrow(new IllegalStateException("写后异常"));
        var lifecycle = new CodingStageLifecycle("turn", journal, new ObjectMapper());
        assertThatThrownBy(() -> lifecycle.start(CodingStage.CODING))
                .isInstanceOf(CodingStageLifecycle.StageEventPersistenceException.class);
        assertThatThrownBy(() -> lifecycle.skipRemaining("UPSTREAM_FAILED", "未执行", null))
                .isInstanceOf(CodingStageLifecycle.StageEventPersistenceException.class);
        verify(journal, times(1)).append(anyString(), anyString(), anyInt(), any(), any());
    }

    /** 不同 Turn 的实例没有共享阶段状态。 */
    @Test void isolatesTurns() {
        var journal = mock(RunEventJournal.class);
        var first = new CodingStageLifecycle("first", journal, new ObjectMapper());
        var second = new CodingStageLifecycle("second", journal, new ObjectMapper());
        first.start(CodingStage.CODING);
        second.start(CodingStage.CODING);
        first.fail("CANCELLED", "CANCELLED", "已停止", null);
        assertThat(second.current()).isEqualTo(CodingStage.CODING);
    }
}
