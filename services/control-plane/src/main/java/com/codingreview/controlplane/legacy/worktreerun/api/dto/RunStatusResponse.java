package com.codingreview.controlplane.legacy.worktreerun.api.dto;

import com.codingreview.controlplane.legacy.worktreerun.application.RunExecutionSnapshot;
import com.codingreview.controlplane.legacy.worktreerun.application.RunExecutionStatus;
import com.codingreview.controlplane.legacy.worktreerun.workflow.model.WorktreeRunResult;

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
    /** 将内部 Run 快照映射为状态查询响应。 */
    public static RunStatusResponse from(RunExecutionSnapshot snapshot) {
        WorktreeRunResult result = snapshot.result();
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
