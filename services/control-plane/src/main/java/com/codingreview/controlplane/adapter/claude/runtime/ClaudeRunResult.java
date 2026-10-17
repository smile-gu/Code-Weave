package com.codingreview.controlplane.adapter.claude.runtime;

import com.codingreview.controlplane.event.AgentEvent;

import java.time.Instant;
import java.util.Objects;

/** Claude 进程结束后的结构化运行结论，不代替过程中的 AgentEvent。 */
public record ClaudeRunResult(
        ClaudeRunStatus status,
        Integer exitCode,
        boolean initialized,
        String nativeSessionId,
        boolean terminalResultReceived,
        AgentEvent terminalEvent,
        Instant startedAt,
        Instant endedAt,
        String diagnostic
) {

    /** 兼容 M5 测试和调用方构造的运行结论。 */
    public ClaudeRunResult(
            ClaudeRunStatus status, Integer exitCode, boolean initialized,
            boolean terminalResultReceived, AgentEvent terminalEvent,
            Instant startedAt, Instant endedAt, String diagnostic
    ) {
        this(status, exitCode, initialized, null, terminalResultReceived, terminalEvent,
                startedAt, endedAt, diagnostic);
    }

    public ClaudeRunResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(endedAt, "endedAt");
        if (endedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("endedAt must not be before startedAt");
        }
        if (terminalResultReceived && terminalEvent == null) {
            throw new IllegalArgumentException("terminalEvent is required when terminalResultReceived is true");
        }
    }
}
