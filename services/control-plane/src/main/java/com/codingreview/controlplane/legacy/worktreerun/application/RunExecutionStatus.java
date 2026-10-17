package com.codingreview.controlplane.legacy.worktreerun.application;

/** HTTP 层可查询的 Run 执行状态。 */
public enum RunExecutionStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    INFRASTRUCTURE_FAILED
}
