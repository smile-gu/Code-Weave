package com.codingreview.controlplane.application.session.error;

/** 表示 Session 当前业务状态与用户请求冲突。 */
public final class CodingSessionConflictException extends RuntimeException {
    private final String code;

    /** 使用稳定错误码和用户可读信息创建冲突异常。 */
    public CodingSessionConflictException(String code, String message) {
        super(message);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        this.code = code;
    }

    /** 返回前端可稳定判断的错误码。 */
    public String code() {
        return code;
    }
}
