package com.codingreview.controlplane.application.session;

import com.codingreview.controlplane.session.model.CodingSession;
import com.codingreview.controlplane.session.model.CodingSessionStatus;
import com.codingreview.controlplane.session.persistence.CodingSessionRepository;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicy;
import com.codingreview.controlplane.workspace.git.model.GitRepositorySnapshot;
import com.codingreview.controlplane.workspace.lock.RepositoryWriteLockManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 验证服务重启时修正异常 Session 并恢复真实仓库写锁。 */
class CodingSessionRecoveryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void marksInterruptedAndMissingWorkspaceSessionsAsFailed() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        Instant now = Instant.parse("2026-08-18T06:00:00Z");
        CodingSession initializing = CodingSession.initializing(
                UUID.randomUUID(), "初始化中", tempDir.toAbsolutePath(), "HEAD", policy(), now);
        CodingSession missing = active(UUID.randomUUID(), tempDir.resolve("missing"), now);
        Path existingPath = tempDir.resolve("existing");
        Files.createDirectories(existingPath);
        CodingSession existing = active(UUID.randomUUID(), existingPath, now);
        repository.save(initializing);
        repository.save(missing);
        repository.save(existing);
        RepositoryWriteLockManager locks = mock(RepositoryWriteLockManager.class);

        new CodingSessionRecoveryService(repository, locks).run(null);

        assertThat(repository.find(initializing.sessionId()).orElseThrow().failureCode())
                .isEqualTo("SESSION_INITIALIZATION_INTERRUPTED");
        assertThat(repository.find(missing.sessionId()).orElseThrow().failureCode())
                .isEqualTo("SESSION_REPOSITORY_MISSING");
        assertThat(repository.find(existing.sessionId()).orElseThrow().status())
                .isEqualTo(CodingSessionStatus.ACTIVE);
        verify(locks).recover(existingPath.toAbsolutePath(), existing.sessionId());
    }

    @Test
    void restoresRepositoryProtectionForCorruptedHistoryOnSecondStartup() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        Path workspace = tempDir.resolve("protected-workspace").toAbsolutePath();
        Files.createDirectories(workspace);
        CodingSession protectedSession = active(
                UUID.randomUUID(), workspace, Instant.parse("2026-09-08T00:00:00Z")
        ).fail("TURN_HISTORY_CORRUPTED", "历史损坏", Instant.parse("2026-09-08T00:00:01Z"));
        repository.save(protectedSession);
        RepositoryWriteLockManager locks = mock(RepositoryWriteLockManager.class);

        new CodingSessionRecoveryService(repository, locks).run(null);

        verify(locks).recover(workspace, protectedSession.sessionId());
    }

    @Test
    void compensatesClosedAcrossTwoRestartsWithoutTouchingNewOwner() throws Exception {
        var repository = new MemoryRepository();
        Path workspace = Files.createDirectory(tempDir.resolve("closed"));
        var closed = active(UUID.randomUUID(), workspace, Instant.now()).close(Instant.now());
        repository.save(closed);
        var first = new com.codingreview.controlplane.workspace.lock.FileRepositoryWriteLockManager(tempDir);
        first.acquire(workspace, closed.sessionId());
        first.close();
        UUID next = UUID.randomUUID();
        for (int restart = 0; restart < 2; restart++) {
            try (var locks = new com.codingreview.controlplane.workspace.lock.FileRepositoryWriteLockManager(tempDir)) {
                new CodingSessionRecoveryService(repository, locks).run(null);
                locks.acquire(workspace, next);
                new CodingSessionRecoveryService(repository, locks).run(null);
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> locks.acquire(workspace, UUID.randomUUID()))
                        .isInstanceOf(com.codingreview.controlplane.workspace.lock.RepositoryWriteLockException.class);
                locks.release(workspace, next);
            }
        }
        assertThat(repository.find(closed.sessionId())).contains(closed);
    }

    @Test
    void closedReleaseFailureDoesNotStopOtherSessions() throws Exception {
        var repository = new MemoryRepository();
        var closed = active(UUID.randomUUID(), Files.createDirectory(tempDir.resolve("closed")), Instant.now())
                .close(Instant.now());
        var active = active(UUID.randomUUID(), Files.createDirectory(tempDir.resolve("active")), Instant.now());
        repository.save(closed);
        repository.save(active);
        var locks = mock(RepositoryWriteLockManager.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("release")).when(locks)
                .release(closed.sourceRepository(), closed.sessionId());
        new CodingSessionRecoveryService(repository, locks).run(null);
        verify(locks).recover(active.sourceRepository(), active.sessionId());
        assertThat(repository.find(closed.sessionId())).contains(closed);
    }

    private CodingSession active(UUID id, Path repository, Instant now) {
        Path root = repository.toAbsolutePath();
        CodingSession initializing = CodingSession.initializing(
                id, "活动会话", root, "main", policy(), now);
        return initializing.activateRealRepository(
                new GitRepositorySnapshot(root, "main", "resolved", false), now);
    }

    private ChangePolicy policy() {
        return new ChangePolicy(List.of("src/**"), List.of("target/**"));
    }

    private static final class MemoryRepository implements CodingSessionRepository {
        private final LinkedHashMap<UUID, CodingSession> sessions = new LinkedHashMap<>();

        @Override
        public void save(CodingSession session) {
            sessions.put(session.sessionId(), session);
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
