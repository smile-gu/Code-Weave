package com.codingreview.controlplane.observability.run.error;

/** Run 事件日志无法解析或完整性校验失败时抛出的异常。 */
public class RunEventReadException extends RuntimeException {

    private final String userMessage;
    private final String diagnostic;

    public RunEventReadException(String userMessage, String diagnostic, Throwable cause) {
        super(userMessage + (diagnostic == null || diagnostic.isBlank() ? "" : " (" + diagnostic + ")"), cause);
        this.userMessage = userMessage;
        this.diagnostic = diagnostic;
    }

    public String userMessage() {
        return userMessage;
    }

    public String diagnostic() {
        return diagnostic;
    }
}
