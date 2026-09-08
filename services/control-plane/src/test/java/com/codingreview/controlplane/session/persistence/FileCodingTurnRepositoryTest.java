package com.codingreview.controlplane.session.persistence;

import com.codingreview.controlplane.session.model.CodingTurn;
import com.codingreview.controlplane.session.model.TurnOutcome;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证 Turn 版本化快照的保存、覆盖和顺序恢复。 */
class FileCodingTurnRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void savesLatestVersionAndListsTurnsBySequence() throws Exception {
        FileCodingTurnRepository repository = new FileCodingTurnRepository(
                tempDir.resolve("runtime").toAbsolutePath(),
                JsonMapper.builder().findAndAddModules().build()
        );
        UUID sessionId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-18T09:00:00Z");
        CodingTurn firstQueued = CodingTurn.queued(
                UUID.randomUUID(), sessionId, 1, "第一轮", now
        );
        CodingTurn firstCompleted = firstQueued.running(now.plusMillis(1))
                .complete(TurnOutcome.NO_CHANGES, 0, null, "没有修改",
                        "已检查", "claude", now.plusSeconds(1));
        CodingTurn second = CodingTurn.queued(
                UUID.randomUUID(), sessionId, 2, "第二轮", now.plusSeconds(2)
        );

        repository.save(second);
        repository.save(firstQueued);
        repository.save(firstCompleted);

        assertThat(repository.find(sessionId, firstQueued.turnId()))
                .contains(firstCompleted);
        assertThat(repository.findAll(sessionId))
                .extracting(CodingTurn::sequence)
                .containsExactly(1, 2);
        Path turns = tempDir.resolve("runtime/sessions")
                .resolve(sessionId.toString()).resolve("turns");
        try (var files = Files.list(turns)) {
            assertThat(files).allMatch(path -> path.toString().endsWith(".json"));
        }
    }

    @Test
    void reportsCorruptedJsonWithoutDeletingOriginalFile() throws Exception {
        Path runtime = tempDir.resolve("runtime").toAbsolutePath();
        FileCodingTurnRepository repository = new FileCodingTurnRepository(
                runtime, JsonMapper.builder().findAndAddModules().build()
        );
        UUID sessionId = UUID.randomUUID();
        Path damaged = runtime.resolve("sessions").resolve(sessionId.toString())
                .resolve("turns/1-" + UUID.randomUUID() + ".json");
        Files.createDirectories(damaged.getParent());
        Files.writeString(damaged, "{broken-json");

        assertThatThrownBy(() -> repository.findAll(sessionId))
                .isInstanceOf(CodingTurnHistoryException.class)
                .satisfies(error -> {
                    CodingTurnHistoryException failure = (CodingTurnHistoryException) error;
                    assertThat(failure.sessionId()).isEqualTo(sessionId);
                    assertThat(failure.file()).isEqualTo(damaged);
                    assertThat(failure.kind()).isEqualTo(CodingTurnHistoryFailureKind.DATA_CORRUPTED);
                });
        assertThat(damaged).exists();
    }

    @Test
    void distinguishesUnsupportedSnapshotVersion() throws Exception {
        Path runtime = tempDir.resolve("runtime-version").toAbsolutePath();
        FileCodingTurnRepository repository = new FileCodingTurnRepository(
                runtime, JsonMapper.builder().findAndAddModules().build()
        );
        UUID sessionId = UUID.randomUUID();
        Path file = runtime.resolve("sessions").resolve(sessionId.toString())
                .resolve("turns/1-" + UUID.randomUUID() + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"schemaVersion\":99,\"turn\":null}");

        assertThatThrownBy(() -> repository.findAll(sessionId))
                .isInstanceOf(CodingTurnHistoryException.class)
                .satisfies(error -> assertThat(((CodingTurnHistoryException) error).kind())
                        .isEqualTo(CodingTurnHistoryFailureKind.VERSION_UNSUPPORTED));
    }

    @Test
    void rejectsSnapshotWhoseFileNameOnlyStartsWithTurnIdentity() throws Exception {
        Path runtime = tempDir.resolve("runtime-identity").toAbsolutePath();
        var mapper = JsonMapper.builder().findAndAddModules().build();
        FileCodingTurnRepository repository = new FileCodingTurnRepository(runtime, mapper);
        UUID sessionId = UUID.randomUUID();
        CodingTurn turn = CodingTurn.queued(
                UUID.randomUUID(), sessionId, 1, "校验文件身份", Instant.now()
        );
        Path file = runtime.resolve("sessions").resolve(sessionId.toString())
                .resolve("turns/1-" + turn.turnId() + "-unexpected.json");
        Files.createDirectories(file.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                file.toFile(), java.util.Map.of("schemaVersion", 1, "turn", turn)
        );

        assertThatThrownBy(() -> repository.findAll(sessionId))
                .isInstanceOf(CodingTurnHistoryException.class)
                .satisfies(error -> assertThat(((CodingTurnHistoryException) error).kind())
                        .isEqualTo(CodingTurnHistoryFailureKind.DATA_CORRUPTED));
    }
}
