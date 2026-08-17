package com.codingreview.controlplane.application.run;

import com.codingreview.controlplane.workflow.m0.model.M0RunResult;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 某次 Run 在当前服务进程中的只读状态快照。 */
public record RunExecutionSnapshot(
        UUID runId,
        RunExecutionStatus status,
        Instant createdAt,
        Instant startedAt,
        Instant endedAt,
        String message,
        M0RunResult result
) {
    public RunExecutionSnapshot {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }

    public boolean terminal() {
        return switch (status) {
            case COMPLETED, FAILED, CANCELLED, INFRASTRUCTURE_FAILED -> true;
            case QUEUED, RUNNING -> false;
        };
    }
}
