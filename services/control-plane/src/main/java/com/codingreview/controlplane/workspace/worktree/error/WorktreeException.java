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

    public WorktreeFailureType failureType() {
        return failureType;
    }

    public String userMessage() {
        return userMessage;
    }

    public String diagnostic() {
        return diagnostic;
    }
}
