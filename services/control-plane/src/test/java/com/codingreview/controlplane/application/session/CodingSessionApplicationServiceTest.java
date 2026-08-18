package com.codingreview.controlplane.application.session;

import com.codingreview.controlplane.application.session.error.CodingSessionCreationException;
import com.codingreview.controlplane.session.model.CodingSession;
import com.codingreview.controlplane.session.model.CodingSessionStatus;
import com.codingreview.controlplane.session.persistence.CodingSessionPersistenceException;
import com.codingreview.controlplane.session.persistence.CodingSessionRepository;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicy;
import com.codingreview.controlplane.workspace.worktree.GitWorktreeManager;
import com.codingreview.controlplane.workspace.worktree.error.WorktreeException;
import com.codingreview.controlplane.workspace.worktree.error.WorktreeFailureType;
import com.codingreview.controlplane.workspace.worktree.model.WorktreeCreateResult;
import com.codingreview.controlplane.workspace.worktree.model.WorktreeHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证 Session 创建时的持久状态转换和 Worktree 失败语义。 */
class CodingSessionApplicationServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsInitializingThenActiveSession() {
        RecordingRepository repository = new RecordingRepository();
        GitWorktreeManager worktreeManager = mock(GitWorktreeManager.class);
        Path source = tempDir.resolve("repository").toAbsolutePath();
        when(worktreeManager.create(any())).thenAnswer(invocation -> {
            var request = (com.codingreview.controlplane.workspace.worktree.model.WorktreeCreateRequest)
                    invocation.getArgument(0);
            return new WorktreeCreateResult(new WorktreeHandle(
                    source,
                    tempDir.resolve("runtime/worktrees").resolve(request.workspaceId()).toAbsolutePath(),
                    request.workspaceId(), request.baseRef(), "resolved-commit", false
            ), List.of());
        });
        CodingSessionApplicationService service = new CodingSessionApplicationService(
                repository, worktreeManager);

        CodingSession session = service.create(command(null, source));

        assertThat(session.status()).isEqualTo(CodingSessionStatus.ACTIVE);
        assertThat(session.title()).isEqualTo("repository");
        assertThat(repository.saved)
                .extracting(CodingSession::status)
                .containsExactly(CodingSessionStatus.INITIALIZING, CodingSessionStatus.ACTIVE);
        assertThat(session.worktreePath().getFileName().toString())
                .isEqualTo(session.sessionId().toString());
    }

    @Test
    void persistsFailedSessionWhenWorktreeCreationFails() {
        RecordingRepository repository = new RecordingRepository();
        GitWorktreeManager worktreeManager = mock(GitWorktreeManager.class);
        when(worktreeManager.create(any())).thenThrow(new WorktreeException(
                WorktreeFailureType.INVALID_BASE_REF,
                "找不到指定的基准版本。",
                "unknown revision"
        ));
        CodingSessionApplicationService service = new CodingSessionApplicationService(
                repository, worktreeManager);

        assertThatThrownBy(() -> service.create(command("失败会话", tempDir.toAbsolutePath())))
                .isInstanceOf(CodingSessionCreationException.class)
                .satisfies(error -> assertThat(((CodingSessionCreationException) error).code())
                        .isEqualTo("INVALID_BASE_REF"));
        assertThat(repository.saved.getLast().status()).isEqualTo(CodingSessionStatus.FAILED);
        assertThat(repository.saved.getLast().failureCode()).isEqualTo("INVALID_BASE_REF");
    }

    @Test
    void cleansUnusedWorktreeWhenActiveSnapshotCannotBeSaved() {
        CodingSessionRepository repository = mock(CodingSessionRepository.class);
        AtomicInteger saves = new AtomicInteger();
        doAnswer(invocation -> {
            if (saves.incrementAndGet() == 2) {
                throw new CodingSessionPersistenceException("disk full");
            }
            return null;
        }).when(repository).save(any());
        GitWorktreeManager worktreeManager = mock(GitWorktreeManager.class);
        Path source = tempDir.resolve("repository").toAbsolutePath();
        WorktreeHandle handle = new WorktreeHandle(
                source,
                tempDir.resolve("runtime/worktrees/session").toAbsolutePath(),
                "temporary",
                "HEAD",
                "resolved-commit",
                false
        );
        when(worktreeManager.create(any())).thenAnswer(invocation -> {
            var request = (com.codingreview.controlplane.workspace.worktree.model.WorktreeCreateRequest)
                    invocation.getArgument(0);
            return new WorktreeCreateResult(new WorktreeHandle(
                    handle.sourceRepository(), handle.worktreePath(), request.workspaceId(),
                    handle.baseRef(), handle.resolvedCommit(), false
            ), List.of());
        });
        CodingSessionApplicationService service = new CodingSessionApplicationService(
                repository, worktreeManager);

        assertThatThrownBy(() -> service.create(command("保存失败", source)))
                .isInstanceOf(CodingSessionCreationException.class)
                .satisfies(error -> assertThat(((CodingSessionCreationException) error).code())
                        .isEqualTo("SESSION_PERSISTENCE_FAILED"));
        verify(worktreeManager).forceCleanup(any());
    }

    private CreateCodingSessionCommand command(String title, Path repository) {
        return new CreateCodingSessionCommand(
                title, repository, "HEAD",
                new ChangePolicy(List.of("src/**"), List.of("target/**"))
        );
    }

    private static final class RecordingRepository implements CodingSessionRepository {
        private final LinkedHashMap<UUID, CodingSession> sessions = new LinkedHashMap<>();
        private final List<CodingSession> saved = new ArrayList<>();

        @Override
        public void save(CodingSession session) {
            sessions.put(session.sessionId(), session);
            saved.add(session);
        }

        @Override
        public Optional<CodingSession> find(UUID sessionId) {
            return Optional.ofNullable(sessions.get(sessionId));
        }

        @Override
        public List<CodingSession> findAll() {
            return List.copyOf(sessions.values());
        }
    }
}
