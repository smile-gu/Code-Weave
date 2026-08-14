package com.codingreview.controlplane.workspace.change.error;

import java.util.Objects;

/** Git 变更采集异常，分别提供用户提示和技术诊断。 */
public class GitChangeException extends RuntimeException {

    private final GitChangeFailureType failureType;
    private final String userMessage;
    private final String diagnostic;

    public GitChangeException(GitChangeFailureType failureType, String userMessage, String diagnostic) {
        super(userMessage + (diagnostic == null || diagnostic.isBlank() ? "" : " (" + diagnostic + ")"));
        this.failureType = Objects.requireNonNull(failureType, "failureType");
        this.userMessage = Objects.requireNonNull(userMessage, "userMessage");
        this.diagnostic = diagnostic;
    }

    public GitChangeFailureType failureType() {
        return failureType;
    }

    public String userMessage() {
        return userMessage;
    }

    public String diagnostic() {
        return diagnostic;
    }
}
