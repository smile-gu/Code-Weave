package com.codingreview.controlplane.observability.run;

import com.codingreview.controlplane.observability.run.error.RunEventReadException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证 Run 事件的持久化、连续序号、重放和完整性检查。 */
class FileRunEventJournalTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @TempDir
    Path tempDir;

    @Test
    void appendsDurableEventsAndReplaysAfterSequence() throws Exception {
        FileRunEventJournal journal = new FileRunEventJournal(tempDir, objectMapper);
        ObjectNode startedPayload = objectMapper.createObjectNode().put("repository", "demo");

        RunEvent first = journal.append("run-1", "run", 1, RunEventType.RUN_CREATED, startedPayload);
        startedPayload.put("repository", "mutated-after-append");
        ((ObjectNode) first.payload()).put("repository", "mutated-through-accessor");
        RunEvent second = journal.append("run-1", "worktree", 1,
                RunEventType.STAGE_COMPLETED, objectMapper.createObjectNode().put("path", "worktrees/run-1"));

        assertThat(first.sequence()).isEqualTo(1);
        assertThat(second.sequence()).isEqualTo(2);
        assertThat(journal.readAfter("run-1", 0)).containsExactly(first, second);
        assertThat(journal.readAfter("run-1", 1)).containsExactly(second);
        assertThat(journal.readAfter("run-1", 2)).isEmpty();
        assertThat(first.payload().path("repository").asText()).isEqualTo("demo");

        Path eventFile = tempDir.resolve("runs/run-1/events.ndjson");
        assertThat(eventFile).exists().isRegularFile();
        assertThat(Files.readAllLines(eventFile, StandardCharsets.UTF_8)).hasSize(2);
    }

    @Test
    void continuesSequenceAfterJournalRestart() {
        RunEventJournal firstJournal = new FileRunEventJournal(tempDir, objectMapper);
        firstJournal.append("run-restart", "run", 1,
                RunEventType.RUN_CREATED, objectMapper.createObjectNode());

        RunEventJournal restartedJournal = new FileRunEventJournal(tempDir, objectMapper);
        RunEvent resumed = restartedJournal.append("run-restart", "worktree", 1,
                RunEventType.STAGE_STARTED, objectMapper.createObjectNode());

        assertThat(resumed.sequence()).isEqualTo(2);
        assertThat(restartedJournal.readAfter("run-restart", 0))
                .extracting(RunEvent::sequence)
                .containsExactly(1L, 2L);
    }

    @Test
    void serializesConcurrentAppendsWithinOneRun() throws Exception {
        RunEventJournal journal = new FileRunEventJournal(tempDir, objectMapper);
        try (var executor = Executors.newFixedThreadPool(6)) {
            List<Callable<RunEvent>> tasks = new ArrayList<>();
            for (int index = 0; index < 30; index++) {
                int line = index;
                tasks.add(() -> journal.append("run-concurrent", "maven", 1,
                        RunEventType.PROCESS_STDOUT,
                        objectMapper.createObjectNode().put("line", line)));
            }
            executor.invokeAll(tasks).forEach(future ->
                    assertThatCode(future::get).doesNotThrowAnyException());
        }

        assertThat(journal.readAfter("run-concurrent", 0))
                .extracting(RunEvent::sequence)
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 30).boxed().toList());
    }

    @Test
    void rejectsCorruptedJsonInsteadOfSilentlySkippingIt() throws Exception {
        Path eventFile = tempDir.resolve("runs/run-corrupt/events.ndjson");
        Files.createDirectories(eventFile.getParent());
        Files.writeString(eventFile, "{not-json}\n", StandardCharsets.UTF_8);

        RunEventJournal journal = new FileRunEventJournal(tempDir, objectMapper);

        assertThatThrownBy(() -> journal.readAfter("run-corrupt", 0))
                .isInstanceOf(RunEventReadException.class)
                .satisfies(error -> {
                    RunEventReadException exception = (RunEventReadException) error;
                    assertThat(exception.userMessage()).contains("完整性校验失败");
                    assertThat(exception.diagnostic()).contains("line=1", "invalid JSON");
                });
    }

    @Test
    void rejectsSequenceGap() throws Exception {
        Path eventFile = tempDir.resolve("runs/run-gap/events.ndjson");
        Files.createDirectories(eventFile.getParent());
        RunEvent first = event("run-gap", UUID.randomUUID(), 1);
        RunEvent third = event("run-gap", UUID.randomUUID(), 3);
        Files.writeString(eventFile,
                objectMapper.writeValueAsString(first) + "\n" + objectMapper.writeValueAsString(third) + "\n",
                StandardCharsets.UTF_8);

        RunEventJournal journal = new FileRunEventJournal(tempDir, objectMapper);

        assertThatThrownBy(() -> journal.readAfter("run-gap", 0))
                .isInstanceOf(RunEventReadException.class)
                .satisfies(error -> assertThat(((RunEventReadException) error).diagnostic())
                        .contains("expected sequence 2 but got 3"));
    }

    @Test
    void rejectsDuplicateEventIdentity() throws Exception {
        Path eventFile = tempDir.resolve("runs/run-duplicate/events.ndjson");
        Files.createDirectories(eventFile.getParent());
        UUID duplicatedId = UUID.randomUUID();
        RunEvent first = event("run-duplicate", duplicatedId, 1);
        RunEvent second = event("run-duplicate", duplicatedId, 2);
        Files.writeString(eventFile,
                objectMapper.writeValueAsString(first) + "\n" + objectMapper.writeValueAsString(second) + "\n",
                StandardCharsets.UTF_8);

        RunEventJournal journal = new FileRunEventJournal(tempDir, objectMapper);

        assertThatThrownBy(() -> journal.readAfter("run-duplicate", 0))
                .isInstanceOf(RunEventReadException.class)
                .satisfies(error -> assertThat(((RunEventReadException) error).diagnostic())
                        .contains("duplicate eventId " + duplicatedId));
    }

    @Test
    void rejectsUnsafeRunAndStageIdentifiers() {
        RunEventJournal journal = new FileRunEventJournal(tempDir, objectMapper);

        assertThatThrownBy(() -> journal.append("../outside", "run", 1,
                RunEventType.RUN_CREATED, objectMapper.createObjectNode()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> journal.append("run-safe", "maven/output", 1,
                RunEventType.STAGE_STARTED, objectMapper.createObjectNode()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private RunEvent event(String runId, UUID eventId, long sequence) {
        return new RunEvent(
                RunEvent.CURRENT_SCHEMA_VERSION,
                eventId,
                runId,
                "run",
                1,
                sequence,
                Instant.parse("2026-08-14T03:00:00Z").plusSeconds(sequence),
                RunEventType.RUN_NOTICE,
                objectMapper.createObjectNode()
        );
    }

}
