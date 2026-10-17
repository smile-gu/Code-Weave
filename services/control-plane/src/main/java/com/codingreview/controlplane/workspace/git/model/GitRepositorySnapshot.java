package com.codingreview.controlplane.workspace.git.model;

import java.nio.file.Path;
import java.util.Objects;

/** 创建 Coding Session 时记录的真实 Git 工作区快照。 */
public record GitRepositorySnapshot(
        Path repositoryRoot,
        String branch,
        String headCommit,
        boolean dirty
) {
    public GitRepositorySnapshot {
        Objects.requireNonNull(repositoryRoot, "repositoryRoot");
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(headCommit, "headCommit");
        if (!repositoryRoot.isAbsolute()) {
            throw new IllegalArgumentException("repositoryRoot must be absolute");
        }
        if (branch.isBlank() || headCommit.isBlank()) {
            throw new IllegalArgumentException("branch and headCommit must not be blank");
        }
    }
}
