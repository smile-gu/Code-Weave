package com.codingreview.controlplane.application.session.error;

/** 表示关闭状态或仓库释放尚未可靠确认，客户端可重试同一关闭请求。 */
public final class CodingSessionCloseException extends RuntimeException {
    private final String code;

    /** 保留稳定错误码及内部故障原因。 */
    public CodingSessionCloseException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** 返回可供客户端判断重试行为的错误码。 */
    public String code() { return code; }
}
