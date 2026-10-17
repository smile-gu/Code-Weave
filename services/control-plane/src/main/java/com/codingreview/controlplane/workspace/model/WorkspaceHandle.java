package com.codingreview.controlplane.workspace.model;

import java.nio.file.Path;
import java.util.Objects;

/** 描述执行基础设施读取代码所需的工作区根目录和比较基线。 */
public record WorkspaceHandle(
        Path workspaceRoot,
        String baselineCommit
) {
    public WorkspaceHandle {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        if (!workspaceRoot.isAbsolute()) {
            throw new IllegalArgumentException("workspaceRoot must be absolute");
        }
        if (baselineCommit == null || baselineCommit.isBlank()) {
            throw new IllegalArgumentException("baselineCommit must not be blank");
        }
        workspaceRoot = workspaceRoot.normalize();
    }
}
