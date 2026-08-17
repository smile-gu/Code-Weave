package com.codingreview.controlplane.api.run.dto.change;

import com.codingreview.controlplane.workspace.change.model.GitChangeSet;
import com.codingreview.controlplane.workspace.change.model.GitChangedFile;

import java.util.List;
import java.util.UUID;

/** 前端 Diff 查看器所需的只读变更快照。 */
public record RunChangesResponse(
        UUID runId,
        String baseCommit,
        String worktree,
        int totalFiles,
        int unifiedDiffBytes,
        List<ChangedFileResponse> files,
        String unifiedDiff
) {
    public RunChangesResponse {
        files = List.copyOf(files);
    }

    /** 将领域变更快照映射为前端可读取的 Diff 响应。 */
    public static RunChangesResponse from(UUID runId, GitChangeSet changes) {
        return new RunChangesResponse(
                runId,
                changes.baseCommit(),
                changes.worktreePath().toString(),
                changes.files().size(),
                changes.unifiedDiffBytes(),
                changes.files().stream().map(ChangedFileResponse::from).toList(),
                changes.unifiedDiff()
        );
    }

    public record ChangedFileResponse(
            String path,
            String oldPath,
            String changeType,
            boolean binary
    ) {
        /** 将单个 Git 变更文件映射为接口字段。 */
        private static ChangedFileResponse from(GitChangedFile file) {
            return new ChangedFileResponse(
                    file.path(),
                    file.oldPath(),
                    file.changeType().name(),
                    file.binary()
            );
        }
    }
}
