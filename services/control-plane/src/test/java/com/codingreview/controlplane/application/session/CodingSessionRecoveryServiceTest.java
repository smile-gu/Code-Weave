package com.codingreview.controlplane.application.session;

import com.codingreview.controlplane.session.model.CodingSession;
import com.codingreview.controlplane.session.model.CodingSessionStatus;
import com.codingreview.controlplane.session.persistence.CodingSessionRepository;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicy;
import com.codingreview.controlplane.workspace.worktree.model.WorktreeHandle;
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

/** 验证服务重启时对中断初始化和缺失 Worktree 的修正。 */
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

        new CodingSessionRecoveryService(repository).run(null);

        assertThat(repository.find(initializing.sessionId()).orElseThrow().failureCode())
                .isEqualTo("SESSION_INITIALIZATION_INTERRUPTED");
        assertThat(repository.find(missing.sessionId()).orElseThrow().failureCode())
                .isEqualTo("SESSION_WORKTREE_MISSING");
        assertThat(repository.find(existing.sessionId()).orElseThrow().status())
                .isEqualTo(CodingSessionStatus.ACTIVE);
    }

    private CodingSession active(UUID id, Path worktree, Instant now) {
        CodingSession initializing = CodingSession.initializing(
                id, "活动会话", tempDir.toAbsolutePath(), "HEAD", policy(), now);
        return initializing.activate(new WorktreeHandle(
                tempDir.toAbsolutePath(), worktree.toAbsolutePath(), id.toString(),
                "HEAD", "resolved", false
        ), now);
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
