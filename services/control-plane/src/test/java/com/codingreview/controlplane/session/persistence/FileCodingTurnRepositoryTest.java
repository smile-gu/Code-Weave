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
}
