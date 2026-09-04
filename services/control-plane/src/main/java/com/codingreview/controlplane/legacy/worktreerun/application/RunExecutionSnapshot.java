package com.codingreview.controlplane.legacy.worktreerun.application;

import com.codingreview.controlplane.legacy.worktreerun.workflow.model.WorktreeRunResult;

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
        WorktreeRunResult result
) {
    public RunExecutionSnapshot {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }

    /** 判断该状态快照是否已经结束。 */
    public boolean terminal() {
        return switch (status) {
            case COMPLETED, FAILED, CANCELLED, INFRASTRUCTURE_FAILED -> true;
            case QUEUED, RUNNING -> false;
        };
    }
}
