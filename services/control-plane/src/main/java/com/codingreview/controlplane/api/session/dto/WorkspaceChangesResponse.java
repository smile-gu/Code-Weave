package com.codingreview.controlplane.api.session.dto;

import com.codingreview.controlplane.workspace.change.model.GitChangeSet;
import com.codingreview.controlplane.workspace.change.model.GitChangedFile;

import java.util.List;

/** 当前 Coding Session 相对活动审查基线的累计变更。 */
public record WorkspaceChangesResponse(
        String baseCommit,
        String workspaceRoot,
        int totalFiles,
        int unifiedDiffBytes,
        List<ChangedFileResponse> files,
        String unifiedDiff
) {
    public WorkspaceChangesResponse {
        files = List.copyOf(files);
    }

    /** 将领域变更快照转换为 Session 级累计变更响应。 */
    public static WorkspaceChangesResponse from(GitChangeSet changes) {
        return new WorkspaceChangesResponse(
                changes.baseCommit(),
                changes.workspaceRoot().toString(),
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
        /** 将单个文件变更映射为接口字段。 */
        private static ChangedFileResponse from(GitChangedFile file) {
            return new ChangedFileResponse(
                    file.path(), file.oldPath(), file.changeType().name(), file.binary()
            );
        }
    }
}
