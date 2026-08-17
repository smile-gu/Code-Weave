package com.codingreview.controlplane.api.run.dto;

import com.codingreview.controlplane.application.run.RunExecutionSnapshot;
import com.codingreview.controlplane.application.run.RunExecutionStatus;
import com.codingreview.controlplane.workflow.m0.model.M0RunResult;

import java.time.Instant;
import java.util.UUID;

/** 面向前端的 Run 状态摘要，不在状态接口中返回完整 Diff 和进程日志。 */
public record RunStatusResponse(
        UUID runId,
        RunExecutionStatus status,
        Instant createdAt,
        Instant startedAt,
        Instant endedAt,
        String message,
        String failedStage,
        String failureCode,
        String worktree,
        Integer changedFiles,
        String mavenStatus
) {
    public static RunStatusResponse from(RunExecutionSnapshot snapshot) {
        M0RunResult result = snapshot.result();
        return new RunStatusResponse(
                snapshot.runId(),
                snapshot.status(),
                snapshot.createdAt(),
                snapshot.startedAt(),
                snapshot.endedAt(),
                snapshot.message(),
                result == null || result.failedStage() == null
                        ? null : result.failedStage().name(),
                result == null || result.failureCode() == null
                        ? null : result.failureCode().name(),
                result == null || result.worktree() == null
                        ? null : result.worktree().handle().worktreePath().toString(),
                result == null || result.changes() == null
                        ? null : result.changes().files().size(),
                result == null || result.maven() == null
                        ? null : result.maven().status().name()
        );
    }
}
