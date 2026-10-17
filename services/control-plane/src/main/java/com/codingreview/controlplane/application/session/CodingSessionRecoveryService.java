package com.codingreview.controlplane.application.session;

import com.codingreview.controlplane.session.model.CodingSession;
import com.codingreview.controlplane.session.model.CodingSessionStatus;
import com.codingreview.controlplane.session.persistence.CodingSessionRepository;
import com.codingreview.controlplane.workspace.lock.RepositoryWriteLockManager;
import com.codingreview.controlplane.workspace.lock.RepositoryWriteLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;

import java.nio.file.Files;
import java.time.Instant;
import java.util.Objects;

/** 服务启动时修正异常 Session，并恢复真实仓库写锁。 */
@Component
@Order(0)
public class CodingSessionRecoveryService implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(CodingSessionRecoveryService.class);

    private final CodingSessionRepository repository;
    private final RepositoryWriteLockManager lockManager;
    private final com.codingreview.controlplane.review.ReviewExecutionGuard reviewGuard;

    /** 装配需要扫描和更新的 Session 仓储。 */
    public CodingSessionRecoveryService(
            CodingSessionRepository repository,
            RepositoryWriteLockManager lockManager,
            com.codingreview.controlplane.review.ReviewExecutionGuard reviewGuard
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.lockManager = Objects.requireNonNull(lockManager, "lockManager");
        this.reviewGuard = reviewGuard;
    }

    /** 扫描平台快照并修正无法继续使用的 Session。 */
    @Override
    public void run(ApplicationArguments args) {
        int recovered = 0;
        int failed = 0;
        for (CodingSession session : repository.findAll()) {
            if (reviewGuard.blocks(session.sessionId())) {
                try {
                    if (session.usesRealRepository()) lockManager.recover(session.sourceRepository(), session.sessionId());
                    failed++;
                } catch (RuntimeException error) {
                    LOGGER.error("Review 保护恢复尚未确认，sessionId={}", session.sessionId());
                    reviewGuard.uncertain(session.sessionId()); failed++;
                }
                continue;
            }
            if (session.status() == CodingSessionStatus.CLOSED) {
                try {
                    if (session.usesRealRepository())
                        lockManager.release(session.sourceRepository(), session.sessionId());
                    recovered++;
                } catch (RuntimeException exception) {
                    LOGGER.error("已关闭 Session 的仓库释放尚未确认，可重试关闭，sessionId={}",
                            session.sessionId(), exception);
                    failed++;
                }
            } else if (session.status() == CodingSessionStatus.INITIALIZING) {
                repository.save(session.fail(
                        "SESSION_INITIALIZATION_INTERRUPTED",
                        "服务在 Session 初始化完成前重启，本次初始化已停止。",
                        Instant.now()
                ));
                failed++;
            } else if (session.status() == CodingSessionStatus.ACTIVE
                    && !Files.isDirectory(session.sourceRepository())) {
                repository.save(session.fail(
                        "SESSION_REPOSITORY_MISSING",
                        "Session 绑定的真实 Git 工作区已不存在，无法继续 Coding。",
                        Instant.now()
                ));
                failed++;
            } else if (requiresRepositoryProtection(session)) {
                try {
                    lockManager.recover(session.sourceRepository(), session.sessionId());
                    recovered++;
                } catch (RepositoryWriteLockException exception) {
                    LOGGER.error("单个 Session 的仓库保护恢复失败，其他 Session 将继续恢复，sessionId={}",
                            session.sessionId(), exception);
                    if (session.status() == CodingSessionStatus.ACTIVE) {
                        try {
                            repository.save(session.fail(
                                    "REPOSITORY_LOCK_RECOVERY_FAILED",
                                    "仓库执行保护无法恢复，当前 Session 已停止继续执行。",
                                    Instant.now()
                            ));
                        } catch (RuntimeException saveFailure) {
                            LOGGER.error("仓库保护恢复失败后无法保存 Session 状态，sessionId={}",
                                    session.sessionId(), saveFailure);
                        }
                    }
                    failed++;
                }
            } else {
                recovered++;
            }
        }
        LOGGER.info("Coding Session 恢复完成，可用={}，修正失败={}", recovered, failed);
    }

    /** 判断活动或安全状态不确定的 Session 是否必须继续占有仓库保护。 */
    private boolean requiresRepositoryProtection(CodingSession session) {
        if (session.status() == CodingSessionStatus.ACTIVE) return true;
        if (session.status() != CodingSessionStatus.FAILED) return false;
        return java.util.Set.of(
                "TURN_HISTORY_CORRUPTED",
                "TURN_HISTORY_VERSION_UNSUPPORTED",
                "TURN_HISTORY_IO_FAILED",
                "PROCESS_CLEANUP_FAILED",
                "REPOSITORY_LOCK_RECOVERY_FAILED"
        ).contains(session.failureCode());
    }
}
