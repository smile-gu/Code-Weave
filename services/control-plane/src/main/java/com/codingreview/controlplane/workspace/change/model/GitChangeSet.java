package com.codingreview.controlplane.workspace.change.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Worktree 相对于任务基准提交的完整变更快照。 */
public record GitChangeSet(
        String baseCommit,
        Path worktreePath,
        List<GitChangedFile> files,
        String unifiedDiff,
        int unifiedDiffBytes
) {
    public GitChangeSet {
        if (baseCommit == null || baseCommit.isBlank()) {
            throw new IllegalArgumentException("baseCommit must not be blank");
        }
        Objects.requireNonNull(worktreePath, "worktreePath");
        if (!worktreePath.isAbsolute()) {
            throw new IllegalArgumentException("worktreePath must be absolute");
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
