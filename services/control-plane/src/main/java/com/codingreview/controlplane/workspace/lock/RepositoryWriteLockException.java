package com.codingreview.controlplane.workspace.lock;

/** 仓库写锁申请失败时携带稳定错误码和诊断信息。 */
public final class RepositoryWriteLockException extends RuntimeException {
    private final String code;
    private final String diagnostic;

    public RepositoryWriteLockException(String code, String message, String diagnostic) {
        this(code, message, diagnostic, null);
    }

    public RepositoryWriteLockException(String code, String message, String diagnostic, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.diagnostic = diagnostic;
    }

    public String code() { return code; }
    public String diagnostic() { return diagnostic; }
}
