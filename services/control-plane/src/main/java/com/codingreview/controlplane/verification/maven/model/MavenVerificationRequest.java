package com.codingreview.controlplane.verification.maven.model;

import com.codingreview.controlplane.workspace.worktree.model.WorktreeHandle;

import java.util.Objects;

/** 一次 Maven 验证所需的受管 Worktree 和尝试次数。 */
public record MavenVerificationRequest(
        String runId,
        WorktreeHandle worktree,
        int attempt
) {
    public MavenVerificationRequest {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        Objects.requireNonNull(worktree, "worktree");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be at least 1");
        }
    }
}
