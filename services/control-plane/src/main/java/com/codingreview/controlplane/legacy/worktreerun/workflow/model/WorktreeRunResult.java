package com.codingreview.controlplane.legacy.worktreerun.workflow.model;

import com.codingreview.controlplane.adapter.claude.runtime.ClaudeRunResult;
import com.codingreview.controlplane.verification.maven.model.MavenVerificationResult;
import com.codingreview.controlplane.workspace.change.model.GitChangeSet;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicyResult;
import com.codingreview.controlplane.legacy.worktreerun.workspace.worktree.model.WorktreeCreateResult;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 汇总 旧版 Worktree Run 各阶段已产生结果和最终失败位置。 */
public record WorktreeRunResult(
        UUID runId,
        WorktreeRunStatus status,
        WorktreeRunStage failedStage,
        WorktreeRunFailureCode failureCode,
        WorktreeCreateResult worktree,
        ClaudeRunResult claude,
        GitChangeSet changes,
        ChangePolicyResult changePolicy,
        MavenVerificationResult maven,
        Instant startedAt,
        Instant endedAt,
        String userMessage,
        String diagnostic
) {
    public WorktreeRunResult {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(endedAt, "endedAt");
        if (endedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("endedAt must not be before startedAt");
        }
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("userMessage must not be blank");
        }
        if (status == WorktreeRunStatus.COMPLETED) {
            if (failedStage != null || failureCode != null) {
                throw new IllegalArgumentException("completed run must not contain failure fields");
            }
            Objects.requireNonNull(worktree, "worktree");
            Objects.requireNonNull(claude, "claude");
            Objects.requireNonNull(changes, "changes");
            Objects.requireNonNull(changePolicy, "changePolicy");
            Objects.requireNonNull(maven, "maven");
        } else if (failedStage == null || failureCode == null) {
            throw new IllegalArgumentException("failed or cancelled run requires failure fields");
        }
    }

    /** 判断五阶段主链路是否全部成功完成。 */
    public boolean completed() {
        return status == WorktreeRunStatus.COMPLETED;
    }
}
