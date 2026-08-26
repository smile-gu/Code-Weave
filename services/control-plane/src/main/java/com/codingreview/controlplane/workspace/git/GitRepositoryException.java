package com.codingreview.controlplane.workspace.git;

/** 真实 Git 仓库检查失败时携带稳定业务错误码。 */
public final class GitRepositoryException extends RuntimeException {
    private final String code;
    private final String diagnostic;

    public GitRepositoryException(String code, String message, String diagnostic) {
        this(code, message, diagnostic, null);
    }

    public GitRepositoryException(String code, String message, String diagnostic, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.diagnostic = diagnostic;
    }

    public String code() { return code; }
    public String diagnostic() { return diagnostic; }
}
