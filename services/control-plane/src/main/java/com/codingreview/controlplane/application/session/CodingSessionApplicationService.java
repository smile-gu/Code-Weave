package com.codingreview.controlplane.application.session;

import com.codingreview.controlplane.application.session.error.CodingSessionCreationException;
import com.codingreview.controlplane.application.session.error.CodingSessionNotFoundException;
import com.codingreview.controlplane.session.model.CodingSession;
import com.codingreview.controlplane.session.persistence.CodingSessionRepository;
import com.codingreview.controlplane.workspace.worktree.GitWorktreeManager;
import com.codingreview.controlplane.workspace.worktree.error.WorktreeException;
import com.codingreview.controlplane.workspace.worktree.model.WorktreeCreateRequest;
import com.codingreview.controlplane.workspace.worktree.model.WorktreeCreateResult;
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
    private final GitWorktreeManager worktreeManager;

    /** 装配 Session 仓储和 Worktree 管理器。 */
    public CodingSessionApplicationService(
            CodingSessionRepository repository,
            GitWorktreeManager worktreeManager
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.worktreeManager = Objects.requireNonNull(worktreeManager, "worktreeManager");
    }

    /** 持久化初始化状态、创建 Worktree 并激活 Session。 */
    public CodingSession create(CreateCodingSessionCommand command) {
        Objects.requireNonNull(command, "command");
        UUID sessionId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        CodingSession initializing = CodingSession.initializing(
                sessionId,
                title(command.title(), command.repository()),
                command.repository(),
                command.baseRef(),
                command.changePolicy(),
                createdAt
        );
        repository.save(initializing);

        try {
            WorktreeCreateResult created = worktreeManager.create(new WorktreeCreateRequest(
                    command.repository(), sessionId.toString(), command.baseRef()
            ));
            CodingSession active = initializing.activate(created.handle(), Instant.now());
            try {
                repository.save(active);
            } catch (RuntimeException exception) {
                cleanupAfterPersistenceFailure(created, exception);
                throw creationFailure(sessionId, "SESSION_PERSISTENCE_FAILED",
                        "Session 工作区已创建，但状态无法可靠保存，已停止初始化。",
                        exception.getMessage(), exception);
            }
            LOGGER.info("Coding Session 创建完成，sessionId={}，repository={}",
                    sessionId, active.sourceRepository());
            return active;
        } catch (CodingSessionCreationException exception) {
            throw exception;
        } catch (WorktreeException exception) {
            persistFailure(initializing, exception.failureType().name(),
                    exception.userMessage(), exception);
            throw creationFailure(sessionId, exception.failureType().name(),
                    exception.userMessage(), exception.diagnostic(), exception);
        } catch (RuntimeException exception) {
            String message = "Coding Session 初始化失败，请查看服务日志后重试。";
            persistFailure(initializing, "SESSION_INITIALIZATION_FAILED", message, exception);
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

    /** 保存初始化失败状态，并避免持久化失败覆盖原始原因。 */
    private void persistFailure(
            CodingSession initializing,
            String code,
            String message,
            RuntimeException original
    ) {
        try {
            repository.save(initializing.fail(code, message, Instant.now()));
        } catch (RuntimeException persistenceFailure) {
            original.addSuppressed(persistenceFailure);
        }
    }

    /** 状态落盘失败时清理尚未投入使用的 Worktree。 */
    private void cleanupAfterPersistenceFailure(
            WorktreeCreateResult created,
            RuntimeException original
    ) {
        try {
            worktreeManager.forceCleanup(created.handle());
        } catch (RuntimeException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
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
