package com.codingreview.controlplane.workspace.change.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** 工作区相对于任务基准提交的完整变更快照。 */
public record GitChangeSet(
        String baseCommit,
        Path workspaceRoot,
        List<GitChangedFile> files,
        String unifiedDiff,
        int unifiedDiffBytes
) {
    public GitChangeSet {
        if (baseCommit == null || baseCommit.isBlank()) {
            throw new IllegalArgumentException("baseCommit must not be blank");
        }
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        if (!workspaceRoot.isAbsolute()) {
            throw new IllegalArgumentException("workspaceRoot must be absolute");
        }
        files = List.copyOf(files);
        Objects.requireNonNull(unifiedDiff, "unifiedDiff");
        if (unifiedDiffBytes < 0) {
            throw new IllegalArgumentException("unifiedDiffBytes must not be negative");
        }
    }

    /** 判断本次采集是否没有产生任何文件变更。 */
    public boolean isEmpty() {
        return files.isEmpty();
    }
}
