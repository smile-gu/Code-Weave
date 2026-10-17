package com.codingreview.controlplane.legacy.worktreerun.workspace.worktree.model;

import java.nio.file.Path;
import java.util.Objects;

/** 已创建 Worktree 的不可变句柄，后续清理必须使用该句柄。 */
public record WorktreeHandle(
        Path sourceRepository,
        Path worktreePath,
        String workspaceId,
        String baseRef,
        String resolvedCommit,
        boolean sourceRepositoryDirty
) {
    public WorktreeHandle {
        Objects.requireNonNull(sourceRepository, "sourceRepository");
        Objects.requireNonNull(worktreePath, "worktreePath");
        Objects.requireNonNull(resolvedCommit, "resolvedCommit");
        if (!sourceRepository.isAbsolute() || !worktreePath.isAbsolute()) {
            throw new IllegalArgumentException("repository paths must be absolute");
        }
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId must not be blank");
        }
        if (baseRef == null || baseRef.isBlank()) {
            throw new IllegalArgumentException("baseRef must not be blank");
        }
    }
}
