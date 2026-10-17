package com.codingreview.controlplane.application.session.error;

import java.util.UUID;

/** Session 初始化失败时保留稳定错误码和用户说明。 */
public class CodingSessionCreationException extends RuntimeException {

    private final UUID sessionId;
    private final String code;
    private final String userMessage;
    private final String diagnostic;

    /** 创建同时携带用户说明和内部诊断的 Session 异常。 */
    public CodingSessionCreationException(
            UUID sessionId,
            String code,
            String userMessage,
            String diagnostic,
            Throwable cause
    ) {
        super(userMessage, cause);
        this.sessionId = sessionId;
        this.code = code;
        this.userMessage = userMessage;
        this.diagnostic = diagnostic;
    }

    /** 返回初始化失败的 Session 标识。 */
    public UUID sessionId() {
        return sessionId;
    }

    /** 返回稳定失败码。 */
    public String code() {
        return code;
    }

    /** 返回可直接展示给用户的失败说明。 */
    public String userMessage() {
        return userMessage;
    }

    /** 返回仅供服务日志使用的技术诊断。 */
    public String diagnostic() {
        return diagnostic;
    }
}
