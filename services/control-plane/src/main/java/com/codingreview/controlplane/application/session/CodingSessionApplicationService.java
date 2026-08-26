package com.codingreview.controlplane.application.session;

import com.codingreview.controlplane.application.session.error.CodingSessionCreationException;
import com.codingreview.controlplane.application.session.error.CodingSessionNotFoundException;
import com.codingreview.controlplane.session.model.CodingSession;
import com.codingreview.controlplane.session.persistence.CodingSessionRepository;
import com.codingreview.controlplane.workspace.git.GitRepositoryInspector;
import com.codingreview.controlplane.workspace.git.GitRepositoryException;
import com.codingreview.controlplane.workspace.git.model.GitRepositorySnapshot;
import com.codingreview.controlplane.workspace.lock.RepositoryWriteLockException;
import com.codingreview.controlplane.workspace.lock.RepositoryWriteLockManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 负责 Coding Session 的创建和持久查询，不介入后续 Coding Turn。 */
@Service
public class CodingSessionApplicationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CodingSessionApplicationService.class);

    private final CodingSessionRepository repository;
    private final GitRepositoryInspector repositoryInspector;
    private final RepositoryWriteLockManager lockManager;

    /** 装配 Session 仓储、真实仓库检查器和仓库写锁。 */
    public CodingSessionApplicationService(
            CodingSessionRepository repository,
            GitRepositoryInspector repositoryInspector,
            RepositoryWriteLockManager lockManager
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.repositoryInspector = Objects.requireNonNull(repositoryInspector, "repositoryInspector");
        this.lockManager = Objects.requireNonNull(lockManager, "lockManager");
    }

    /** 检查并锁定真实 Git 工作区，然后持久化可运行 Session。 */
    public CodingSession create(CreateCodingSessionCommand command) {
        Objects.requireNonNull(command, "command");
        UUID sessionId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        GitRepositorySnapshot snapshot;
        try {
            snapshot = repositoryInspector.inspect(command.repository());
        } catch (GitRepositoryException exception) {
            throw creationFailure(sessionId, exception.code(), exception.getMessage(),
                    exception.diagnostic(), exception);
        }
        if (snapshot.dirty() && !command.includeExistingChanges()) {
            throw creationFailure(
                    sessionId,
                    "PRE_EXISTING_CHANGES_REQUIRE_CONFIRMATION",
                    "仓库存在创建 Session 前的未提交修改；请先处理这些修改，或明确选择将其纳入本 Session。",
                    snapshot.repositoryRoot().toString(),
                    new IllegalStateException("pre-existing workspace changes were not accepted")
            );
        }
        CodingSession initializing = CodingSession.initializing(
                sessionId,
                title(command.title(), snapshot.repositoryRoot()),
                snapshot.repositoryRoot(),
                snapshot.branch(),
                command.changePolicy(),
                createdAt
        );

        try {
            lockManager.acquire(snapshot.repositoryRoot(), sessionId);
            try {
                repository.save(initializing);
                CodingSession active = initializing.activateRealRepository(snapshot, Instant.now());
                repository.save(active);
                LOGGER.info("Coding Session 创建完成，sessionId={}，repository={}",
                        sessionId, active.sourceRepository());
                return active;
            } catch (RuntimeException exception) {
                releaseAfterFailure(snapshot, sessionId, exception);
                throw creationFailure(sessionId, "SESSION_PERSISTENCE_FAILED",
                        "Session 状态无法可靠保存，已释放仓库写锁。",
                        exception.getMessage(), exception);
            }
        } catch (CodingSessionCreationException exception) {
            throw exception;
        } catch (RepositoryWriteLockException exception) {
            throw creationFailure(sessionId, exception.code(), exception.getMessage(),
                    exception.diagnostic(), exception);
        } catch (RuntimeException exception) {
            String message = "Coding Session 初始化失败，请查看服务日志后重试。";
            releaseAfterFailure(snapshot, sessionId, exception);
            throw creationFailure(sessionId, "SESSION_INITIALIZATION_FAILED",
                    message, exception.getMessage(), exception);
        }
    }

    /** 查询必须存在的 Coding Session。 */
    public CodingSession get(UUID sessionId) {
        return repository.find(sessionId)
                .orElseThrow(() -> new CodingSessionNotFoundException(sessionId));
    }

    /** 查询最近更新的 Coding Session。 */
    public List<CodingSession> recent(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        return repository.findRecent(limit);
    }

    /** 初始化失败时释放尚未投入使用的仓库写锁。 */
    private void releaseAfterFailure(
            GitRepositorySnapshot snapshot,
            UUID sessionId,
            RuntimeException original
    ) {
        try {
            lockManager.release(snapshot.repositoryRoot(), sessionId);
        } catch (RuntimeException releaseFailure) {
            original.addSuppressed(releaseFailure);
        }
    }

    /** 为未显式命名的 Session 生成稳定可读标题。 */
    private String title(String requestedTitle, Path repositoryPath) {
        if (requestedTitle != null && !requestedTitle.isBlank()) {
            return requestedTitle;
        }
        Path fileName = repositoryPath.normalize().getFileName();
        return fileName == null ? "Coding Session" : fileName.toString();
    }

    /** 创建携带稳定业务信息的 Session 初始化异常。 */
    private CodingSessionCreationException creationFailure(
            UUID sessionId,
            String code,
            String message,
            String diagnostic,
            RuntimeException cause
    ) {
        LOGGER.error("Coding Session 初始化失败，sessionId={}，code={}，diagnostic={}",
                sessionId, code, diagnostic, cause);
        return new CodingSessionCreationException(
                sessionId, code, message, diagnostic, cause
        );
    }
}
