package com.codingreview.controlplane.application.run;

/** HTTP 层可查询的 Run 执行状态。 */
public enum RunExecutionStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    INFRASTRUCTURE_FAILED
}
