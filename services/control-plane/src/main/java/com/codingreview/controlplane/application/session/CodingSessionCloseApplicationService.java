package com.codingreview.controlplane.application.session;

import com.codingreview.controlplane.application.session.error.CodingSessionCloseException;
import com.codingreview.controlplane.application.session.error.CodingSessionConflictException;
import com.codingreview.controlplane.application.session.error.CodingSessionNotFoundException;
import com.codingreview.controlplane.session.model.CodingSession;
import com.codingreview.controlplane.session.model.CodingSessionStatus;
import com.codingreview.controlplane.session.persistence.CodingSessionRepository;
import com.codingreview.controlplane.workspace.lock.RepositoryWriteLockManager;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.UUID;

/** 在关闭状态可靠保存后幂等释放仓库，不操作真实代码或取消执行。 */
@Service
public final class CodingSessionCloseApplicationService {
    private final CodingSessionRepository sessions;
    private final CodingTurnApplicationService turns;
    private final SessionOperationLockManager operations;
    private final RepositoryWriteLockManager repositories;

    /** 复用 Turn 与基线的操作锁及仓库所有权管理器。 */
    public CodingSessionCloseApplicationService(CodingSessionRepository sessions,
            CodingTurnApplicationService turns, SessionOperationLockManager operations,
            RepositoryWriteLockManager repositories) {
        this.sessions = sessions;
        this.turns = turns;
        this.operations = operations;
        this.repositories = repositories;
    }

    /** 只有状态保存与目标所有权释放均确认后才返回成功，重复调用仅补偿释放。 */
    public CodingSession close(UUID sessionId) {
        synchronized (operations.lock(sessionId)) {
            CodingSession session = sessions.find(sessionId)
                    .orElseThrow(() -> new CodingSessionNotFoundException(sessionId));
            if (!session.usesRealRepository() || (session.status() != CodingSessionStatus.ACTIVE
                    && session.status() != CodingSessionStatus.CLOSED))
                throw new CodingSessionConflictException("SESSION_NOT_CLOSABLE", "当前会话不支持正常关闭。");
            if (session.status() == CodingSessionStatus.ACTIVE) {
                turns.assertIdleAndSafeForClose(sessionId);
                CodingSession target = session.close(Instant.now());
                try {
                    sessions.save(target);
                } catch (RuntimeException failure) {
                    boolean confirmed = false;
                    try {
                        confirmed = sessions.find(sessionId).filter(target::equals).isPresent();
                    } catch (RuntimeException readFailure) {
                        failure.addSuppressed(readFailure);
                    }
                    if (!confirmed) throw new CodingSessionCloseException("SESSION_CLOSE_STATE_WRITE_FAILED",
                            "关闭状态无法确认保存，仓库保护已保留，请重试。", failure);
                }
                session = target;
            }
            try {
                repositories.release(session.sourceRepository(), sessionId);
            } catch (RuntimeException failure) {
                throw new CodingSessionCloseException("SESSION_CLOSE_RELEASE_PENDING",
                        "会话已关闭，仓库释放尚未确认，请重试释放。", failure);
            }
            return session;
        }
    }
}
