package com.codingreview.controlplane.adapter.claude.runtime;

import com.codingreview.controlplane.event.AgentEvent;
import com.codingreview.controlplane.event.AgentEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证运行结果模型的时间和终态字段约束。 */
class ClaudeRunResultTest {

    private final Instant startedAt = Instant.parse("2026-08-13T03:00:00Z");
    private final Instant endedAt = Instant.parse("2026-08-13T03:00:01Z");

    @Test
    void acceptsCompletedResultWithTerminalEvent() {
        AgentEvent terminalEvent = new AgentEvent(
                AgentEventType.RUNTIME_COMPLETED,
                "session-1",
                new ObjectMapper().createObjectNode()
        );

        ClaudeRunResult result = new ClaudeRunResult(
                ClaudeRunStatus.COMPLETED,
                0,
                true,
                true,
                terminalEvent,
                startedAt,
                endedAt,
                null
        );

        assertThat(result.status()).isEqualTo(ClaudeRunStatus.COMPLETED);
        assertThat(result.terminalEvent()).isSameAs(terminalEvent);
    }

    @Test
    void rejectsTerminalFlagWithoutTerminalEvent() {
        assertThatThrownBy(() -> new ClaudeRunResult(
                ClaudeRunStatus.COMPLETED,
                0,
                true,
                true,
                null,
                startedAt,
                endedAt,
                null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEndTimeBeforeStartTime() {
        assertThatThrownBy(() -> new ClaudeRunResult(
                ClaudeRunStatus.PROCESS_FAILED,
                1,
                false,
                false,
                null,
                endedAt,
                startedAt,
                "process failed"
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
