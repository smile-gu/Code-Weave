package com.codingreview.controlplane.legacy.worktreerun.workflow.model;

/** 工作流层用于短路和前端判断的稳定失败类型。 */
public enum WorktreeRunFailureCode {
    WORKTREE_FAILED,
    CODING_FAILED,
    NO_CHANGES,
    CHANGE_COLLECTION_FAILED,
    CHANGE_POLICY_REJECTED,
    MAVEN_FAILED,
    CANCELLED,
    INTERNAL_ERROR
}
