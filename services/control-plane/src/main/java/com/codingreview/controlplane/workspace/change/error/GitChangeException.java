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

    /** 返回稳定的 Git 变更失败类型。 */
    public GitChangeFailureType failureType() {
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
