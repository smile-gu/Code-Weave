package com.codingreview.controlplane.legacy.worktreerun.workspace.worktree.model;

/** Worktree 创建过程中需要向用户展示的提醒类型。 */
public enum WorktreeNoticeType {
    /** 源仓库存在未提交修改，这些修改不会进入隔离 Worktree。 */
    SOURCE_REPOSITORY_DIRTY
}
