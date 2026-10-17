package com.codingreview.controlplane.session.guard;

/** 真实 Git 工作区偏离 Session 创建基线时的稳定业务异常。 */
public final class SessionWorkspaceDriftException extends RuntimeException {
    private final String code;

    public SessionWorkspaceDriftException(String code, String message) {
        super(message);
        if (code == null || code.isBlank()) throw new IllegalArgumentException("code must not be blank");
        this.code = code;
    }

    public String code() { return code; }
}
