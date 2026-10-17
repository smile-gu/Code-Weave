package com.codingreview.controlplane.session.persistence;

/** Session 快照无法可靠读写时抛出的基础设施异常。 */
public class CodingSessionPersistenceException extends RuntimeException {

    /** 使用原始异常创建持久化失败。 */
    public CodingSessionPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 使用业务说明创建持久化失败。 */
    public CodingSessionPersistenceException(String message) {
        super(message);
    }
}
