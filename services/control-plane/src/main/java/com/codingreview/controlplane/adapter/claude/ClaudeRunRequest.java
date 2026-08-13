package com.codingreview.controlplane.adapter.claude;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ClaudeRunRequest(
        UUID sessionId,
        Path worktree,
        String taskPrompt,
        List<String> acceptanceCriteria,
        List<String> allowedPaths,
        List<String> deniedPaths
) {
    public ClaudeRunRequest {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(worktree, "worktree");
        if (!worktree.isAbsolute()) {
            throw new IllegalArgumentException("worktree must be absolute");
        }
        if (taskPrompt == null || taskPrompt.isBlank()) {
            throw new IllegalArgumentException("taskPrompt must not be blank");
        }
        acceptanceCriteria = List.copyOf(acceptanceCriteria);
        allowedPaths = List.copyOf(allowedPaths);
        deniedPaths = List.copyOf(deniedPaths);
    }
}

