package com.codingreview.controlplane.application.session.error;

import java.util.UUID;

/** 查询不存在的 Coding Session 时返回稳定业务异常。 */
public class CodingSessionNotFoundException extends RuntimeException {

    private final UUID sessionId;

    /** 使用未找到的 Session 标识创建异常。 */
    public CodingSessionNotFoundException(UUID sessionId) {
        super("找不到指定的 Coding Session，请确认会话标识是否正确。");
        this.sessionId = sessionId;
    }

    /** 返回未找到的 Session 标识。 */
    public UUID sessionId() {
        return sessionId;
    }
}
