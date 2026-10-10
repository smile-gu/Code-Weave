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
        int unifiedDiffBytes,
        @com.fasterxml.jackson.annotation.JsonIgnore byte[] rawDiff
) {
    public GitChangeSet(String baseCommit, Path workspaceRoot, List<GitChangedFile> files,
            String unifiedDiff, int unifiedDiffBytes) {
        this(baseCommit, workspaceRoot, files, unifiedDiff, unifiedDiffBytes,
                unifiedDiff.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Override public byte[] rawDiff() { return rawDiff.clone(); }
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
        rawDiff = Objects.requireNonNull(rawDiff, "rawDiff").clone();
        if (unifiedDiffBytes < 0) {
            throw new IllegalArgumentException("unifiedDiffBytes must not be negative");
        }
    }

    /** 判断本次采集是否没有产生任何文件变更。 */
    public boolean isEmpty() {
        return files.isEmpty();
    }
}
