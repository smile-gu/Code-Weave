package com.codingreview.controlplane.session.persistence;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/** 携带受影响 Session、原始文件和失败类别的 Turn 历史读取异常。 */
public final class CodingTurnHistoryException extends CodingSessionPersistenceException {
    private final UUID sessionId;
    private final Path file;
    private final CodingTurnHistoryFailureKind kind;

    /** 创建可供恢复流程隔离处理的历史读取异常。 */
    public CodingTurnHistoryException(
            UUID sessionId,
            Path file,
            CodingTurnHistoryFailureKind kind,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    /** 返回受影响的 Session。 */
    public UUID sessionId() { return sessionId; }
    /** 返回保持原样的异常快照路径。 */
    public Path file() { return file; }
    /** 返回可由恢复流程稳定判断的失败类别。 */
    public CodingTurnHistoryFailureKind kind() { return kind; }
}
