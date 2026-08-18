package com.codingreview.controlplane.session.persistence;

import com.codingreview.controlplane.session.model.CodingSession;
import com.codingreview.controlplane.session.model.CodingSessionStatus;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicy;
import com.codingreview.controlplane.workspace.worktree.model.WorktreeHandle;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 Session JSON 快照的写入、覆盖、排序和损坏隔离。 */
class FileCodingSessionRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void savesAndReloadsActiveSessionWithStableDocument() throws Exception {
        FileCodingSessionRepository repository = repository();
        CodingSession session = activeSession(
                UUID.randomUUID(), "用户模块", Instant.parse("2026-08-18T06:00:00Z"));

        repository.save(session);

        assertThat(repository.find(session.sessionId())).contains(session);
        Path sessionDirectory = tempDir.resolve("runtime/sessions").resolve(session.sessionId().toString());
        assertThat(sessionDirectory.resolve("session.json")).isRegularFile();
        try (var files = Files.list(sessionDirectory)) {
            assertThat(files).allMatch(path -> path.getFileName().toString().equals("session.json"));
        }
    }

    @Test
    void ordersRecentSessionsAndSkipsCorruptedSnapshot() throws Exception {
        FileCodingSessionRepository repository = repository();
        CodingSession older = activeSession(
                UUID.randomUUID(), "较早会话", Instant.parse("2026-08-18T05:00:00Z"));
        CodingSession newer = activeSession(
                UUID.randomUUID(), "最近会话", Instant.parse("2026-08-18T07:00:00Z"));
        repository.save(older);
        repository.save(newer);

        Path corrupted = tempDir.resolve("runtime/sessions")
                .resolve(UUID.randomUUID().toString()).resolve("session.json");
        Files.createDirectories(corrupted.getParent());
        Files.writeString(corrupted, "{not-json", StandardCharsets.UTF_8);

        assertThat(repository.findRecent(20))
                .extracting(CodingSession::title)
                .containsExactly("最近会话", "较早会话");
    }

    @Test
    void replacesPreviousSnapshotWithoutLeavingTemporaryFiles() throws Exception {
        FileCodingSessionRepository repository = repository();
        CodingSession active = activeSession(
                UUID.randomUUID(), "待更新会话", Instant.parse("2026-08-18T06:00:00Z"));
        CodingSession failed = active.fail(
                "SESSION_WORKTREE_MISSING", "Worktree 已不存在。", active.updatedAt().plusSeconds(5));

        repository.save(active);
        repository.save(failed);

        assertThat(repository.find(active.sessionId())).contains(failed);
        Path sessionDirectory = tempDir.resolve("runtime/sessions")
                .resolve(active.sessionId().toString());
        try (var files = Files.list(sessionDirectory)) {
            assertThat(files).extracting(path -> path.getFileName().toString())
                    .containsExactly("session.json");
        }
    }

    private FileCodingSessionRepository repository() {
        return new FileCodingSessionRepository(
                tempDir.resolve("runtime").toAbsolutePath(),
                JsonMapper.builder().findAndAddModules().build()
        );
    }

    private CodingSession activeSession(UUID sessionId, String title, Instant time) throws Exception {
        Path repository = tempDir.resolve("repository-" + sessionId).toAbsolutePath();
        Path worktree = tempDir.resolve("runtime/worktrees").resolve(sessionId.toString()).toAbsolutePath();
        Files.createDirectories(repository);
        Files.createDirectories(worktree);
        CodingSession initializing = CodingSession.initializing(
                sessionId, title, repository, "HEAD",
                new ChangePolicy(List.of("src/**"), List.of("target/**")),
                time.minusSeconds(10)
        );
        return initializing.activate(new WorktreeHandle(
                repository, worktree, sessionId.toString(), "HEAD", "abc123", false
        ), time);
    }
}
