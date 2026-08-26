package com.codingreview.controlplane.application.session;

import com.codingreview.controlplane.application.session.error.CodingSessionCreationException;
import com.codingreview.controlplane.session.model.CodingSession;
import com.codingreview.controlplane.session.model.CodingSessionStatus;
import com.codingreview.controlplane.session.persistence.CodingSessionPersistenceException;
import com.codingreview.controlplane.session.persistence.CodingSessionRepository;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicy;
import com.codingreview.controlplane.workspace.git.GitRepositoryInspector;
import com.codingreview.controlplane.workspace.git.GitRepositoryException;
import com.codingreview.controlplane.workspace.git.model.GitRepositorySnapshot;
import com.codingreview.controlplane.workspace.lock.RepositoryWriteLockManager;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证真实 Git 工作区 Session 的创建、锁定和失败释放。 */
class CodingSessionApplicationServiceTest {
    @TempDir Path tempDir;

    @Test
    void persistsInitializingThenActiveSessionOnRealRepository() {
        RecordingRepository repository = new RecordingRepository();
        GitRepositoryInspector inspector = mock(GitRepositoryInspector.class);
        RepositoryWriteLockManager locks = mock(RepositoryWriteLockManager.class);
        Path source = tempDir.resolve("repository").toAbsolutePath();
        when(inspector.inspect(source)).thenReturn(new GitRepositorySnapshot(source, "main", "resolved-commit", false));
        CodingSessionApplicationService service = new CodingSessionApplicationService(repository, inspector, locks);

        CodingSession session = service.create(command(null, source));

        assertThat(session.status()).isEqualTo(CodingSessionStatus.ACTIVE);
        assertThat(session.title()).isEqualTo("repository");
        assertThat(session.worktreePath()).isEqualTo(source);
        assertThat(session.baseRef()).isEqualTo("main");
        assertThat(session.resolvedCommit()).isEqualTo("resolved-commit");
        assertThat(repository.saved).extracting(CodingSession::status)
                .containsExactly(CodingSessionStatus.INITIALIZING, CodingSessionStatus.ACTIVE);
        verify(locks).acquire(source, session.sessionId());
    }

    @Test
    void reportsStableFailureWhenRepositoryInspectionFails() {
        RecordingRepository repository = new RecordingRepository();
        GitRepositoryInspector inspector = mock(GitRepositoryInspector.class);
        RepositoryWriteLockManager locks = mock(RepositoryWriteLockManager.class);
        Path source = tempDir.resolve("missing").toAbsolutePath();
        when(inspector.inspect(source)).thenThrow(new GitRepositoryException("INVALID_GIT_REPOSITORY", "指定目录不是可用的 Git 仓库。", "not a repository"));
        CodingSessionApplicationService service = new CodingSessionApplicationService(repository, inspector, locks);

        assertThatThrownBy(() -> service.create(command("失败会话", source)))
                .isInstanceOf(CodingSessionCreationException.class)
                .satisfies(error -> assertThat(((CodingSessionCreationException) error).code()).isEqualTo("INVALID_GIT_REPOSITORY"));
        assertThat(repository.saved).isEmpty();
    }

    @Test
    void releasesRepositoryLockWhenSnapshotCannotBeSaved() {
        CodingSessionRepository repository = mock(CodingSessionRepository.class);
        AtomicInteger saves = new AtomicInteger();
        doAnswer(invocation -> { if (saves.incrementAndGet() == 2) throw new CodingSessionPersistenceException("disk full"); return null; }).when(repository).save(any());
        GitRepositoryInspector inspector = mock(GitRepositoryInspector.class);
        RepositoryWriteLockManager locks = mock(RepositoryWriteLockManager.class);
        Path source = tempDir.resolve("repository").toAbsolutePath();
        when(inspector.inspect(source)).thenReturn(new GitRepositorySnapshot(source, "main", "resolved-commit", false));
        CodingSessionApplicationService service = new CodingSessionApplicationService(repository, inspector, locks);

        assertThatThrownBy(() -> service.create(command("保存失败", source)))
                .isInstanceOf(CodingSessionCreationException.class)
                .satisfies(error -> assertThat(((CodingSessionCreationException) error).code()).isEqualTo("SESSION_PERSISTENCE_FAILED"));
        verify(locks).release(any(), any());
    }

    @Test
    void reportsRepositoryBusyWithoutPersistingSession() {
        RecordingRepository repository = new RecordingRepository();
        GitRepositoryInspector inspector = mock(GitRepositoryInspector.class);
        RepositoryWriteLockManager locks = mock(RepositoryWriteLockManager.class);
        Path source = tempDir.resolve("repository").toAbsolutePath();
        when(inspector.inspect(source)).thenReturn(new GitRepositorySnapshot(source, "main", "resolved-commit", false));
        doThrow(new com.codingreview.controlplane.workspace.lock.RepositoryWriteLockException("REPOSITORY_BUSY", "仓库已被占用。", source.toString())).when(locks).acquire(any(), any());
        CodingSessionApplicationService service = new CodingSessionApplicationService(repository, inspector, locks);

        assertThatThrownBy(() -> service.create(command("冲突会话", source)))
                .isInstanceOf(CodingSessionCreationException.class)
                .satisfies(error -> assertThat(((CodingSessionCreationException) error).code()).isEqualTo("REPOSITORY_BUSY"));
        assertThat(repository.saved).isEmpty();
    }

    private CreateCodingSessionCommand command(String title, Path repository) {
        return new CreateCodingSessionCommand(title, repository, "HEAD", new ChangePolicy(List.of("src/**"), List.of("target/**")), false);
    }

    private static final class RecordingRepository implements CodingSessionRepository {
        private final LinkedHashMap<UUID, CodingSession> sessions = new LinkedHashMap<>();
        private final List<CodingSession> saved = new ArrayList<>();
        @Override public void save(CodingSession session) { sessions.put(session.sessionId(), session); saved.add(session); }
        @Override public Optional<CodingSession> find(UUID sessionId) { return Optional.ofNullable(sessions.get(sessionId)); }
        @Override public List<CodingSession> findAll() { return List.copyOf(sessions.values()); }
    }
}
