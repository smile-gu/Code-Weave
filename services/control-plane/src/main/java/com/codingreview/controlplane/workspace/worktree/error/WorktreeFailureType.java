package com.codingreview.controlplane.workspace.worktree.error;

/** Worktree 操作失败的机器可判定类型。 */
public enum WorktreeFailureType {
    INVALID_REPOSITORY,
    NOT_GIT_REPOSITORY,
    INVALID_BASE_REF,
    INVALID_WORKTREE_PATH,
    WORKTREE_ALREADY_EXISTS,
    CREATE_FAILED,
    CLEANUP_REJECTED_DIRTY,
    CLEANUP_FAILED,
    GIT_COMMAND_FAILED
}
