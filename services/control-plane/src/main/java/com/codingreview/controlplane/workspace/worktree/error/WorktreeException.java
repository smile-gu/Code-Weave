package com.codingreview.controlplane.workspace.worktree.error;

import java.util.Objects;

/** Worktree 业务操作异常，区分用户提示和技术诊断。 */
public class WorktreeException extends RuntimeException {

    private final WorktreeFailureType failureType;
    private final String userMessage;
    private final String diagnostic;

    public WorktreeException(WorktreeFailureType failureType, String userMessage, String diagnostic) {
        super(userMessage + (diagnostic == null || diagnostic.isBlank() ? "" : " (" + diagnostic + ")"));
        this.failureType = Objects.requireNonNull(failureType, "failureType");
        this.userMessage = Objects.requireNonNull(userMessage, "userMessage");
        this.diagnostic = diagnostic;
    }

    /** 返回稳定的 Worktree 失败类型。 */
    public WorktreeFailureType failureType() {
        return failureType;
    }

    /** 返回面向用户的失败说明。 */
    public String userMessage() {
        return userMessage;
    }

    /** 返回供日志排查使用的技术诊断。 */
    public String diagnostic() {
        return diagnostic;
    }
}
