package com.codingreview.controlplane.observability.run.error;

/** Run 事件无法可靠落盘时抛出的致命异常。 */
public class RunEventWriteException extends RuntimeException {

    private final String userMessage;
    private final String diagnostic;

    public RunEventWriteException(String userMessage, String diagnostic, Throwable cause) {
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
