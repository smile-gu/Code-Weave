package com.codingreview.controlplane.legacy.worktreerun.workspace.worktree.model;

import java.nio.file.Path;
import java.util.Objects;

/** Git 仓库的最小状态快照。 */
public record GitRepositoryStatus(
        Path repository,
        boolean dirty,
        String headCommit
) {
    public GitRepositoryStatus {
        Objects.requireNonNull(repository, "repository");
        if (!repository.isAbsolute()) {
            throw new IllegalArgumentException("repository must be absolute");
        }
    }
}
