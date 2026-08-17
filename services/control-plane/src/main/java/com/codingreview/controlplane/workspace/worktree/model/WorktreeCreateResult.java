package com.codingreview.controlplane.workspace.worktree.model;

import java.util.List;
import java.util.Objects;

/** Worktree 创建成功后的句柄和用户提醒。 */
public record WorktreeCreateResult(
        WorktreeHandle handle,
        List<WorktreeNotice> notices
) {
    public WorktreeCreateResult {
        Objects.requireNonNull(handle, "handle");
        notices = List.copyOf(notices);
    }
}
