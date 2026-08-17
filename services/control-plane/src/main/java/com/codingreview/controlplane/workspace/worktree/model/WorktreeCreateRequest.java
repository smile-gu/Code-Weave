package com.codingreview.controlplane.workspace.worktree.model;

import java.nio.file.Path;
import java.util.Objects;

/** 创建一次隔离 Worktree 所需的业务输入。 */
public record WorktreeCreateRequest(
        Path repository,
        String runId,
        String baseRef
) {
    public WorktreeCreateRequest {
        Objects.requireNonNull(repository, "repository");
        if (!repository.isAbsolute()) {
            throw new IllegalArgumentException("repository must be absolute");
        }
        if (runId == null || !runId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("runId contains unsupported characters");
        }
        baseRef = baseRef == null || baseRef.isBlank() ? "HEAD" : baseRef.trim();
        if (baseRef.startsWith("-")) {
            throw new IllegalArgumentException("baseRef must not start with '-'");
        }
    }
}
