package com.codingreview.controlplane.workspace.change.error;

/** Git 变更采集失败的机器可判定类型。 */
public enum GitChangeFailureType {
    INVALID_WORKSPACE,
    BASE_COMMIT_UNAVAILABLE,
    DIFF_TOO_LARGE,
    GIT_COMMAND_FAILED,
    COLLECT_FAILED
}
