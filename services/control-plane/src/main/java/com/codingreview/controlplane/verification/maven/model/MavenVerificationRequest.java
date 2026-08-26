package com.codingreview.controlplane.verification.maven.model;

import java.nio.file.Path;
import java.util.Objects;

/** 一次 Maven 验证所需的工作目录和尝试次数。 */
public record MavenVerificationRequest(
        String runId,
        Path workspaceRoot,
        int attempt
) {
    public MavenVerificationRequest {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        if (!workspaceRoot.isAbsolute()) {
            throw new IllegalArgumentException("workspaceRoot must be absolute");
        }
        workspaceRoot = workspaceRoot.normalize();
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be at least 1");
        }
    }
}
