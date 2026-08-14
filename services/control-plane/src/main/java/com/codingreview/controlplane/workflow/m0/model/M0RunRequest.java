package com.codingreview.controlplane.workflow.m0.model;

import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicy;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 启动一次 M0 Coding Run 所需的完整业务输入。 */
public record M0RunRequest(
        UUID runId,
        Path repository,
        String baseRef,
        String taskPrompt,
        List<String> acceptanceCriteria,
        ChangePolicy changePolicy
) {
    public M0RunRequest {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(repository, "repository");
        if (!repository.isAbsolute()) {
            throw new IllegalArgumentException("repository must be absolute");
        }
        baseRef = baseRef == null || baseRef.isBlank() ? "HEAD" : baseRef.trim();
        if (baseRef.startsWith("-")) {
            throw new IllegalArgumentException("baseRef must not start with '-'");
        }
        if (taskPrompt == null || taskPrompt.isBlank()) {
            throw new IllegalArgumentException("taskPrompt must not be blank");
        }
        acceptanceCriteria = List.copyOf(acceptanceCriteria);
        Objects.requireNonNull(changePolicy, "changePolicy");
    }

    public String runIdValue() {
        return runId.toString();
    }
}
