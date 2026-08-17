package com.codingreview.controlplane.application.run;

import com.codingreview.controlplane.application.run.error.RunQueueFullException;
import com.codingreview.controlplane.observability.run.RunEvent;
import com.codingreview.controlplane.observability.run.RunEventJournal;
import com.codingreview.controlplane.observability.run.RunEventType;
import com.codingreview.controlplane.workflow.m0.M0RunOrchestrator;
import com.codingreview.controlplane.workflow.m0.model.M0RunFailureCode;
import com.codingreview.controlplane.workflow.m0.model.M0RunResult;
import com.codingreview.controlplane.workflow.m0.model.M0RunStage;
import com.codingreview.controlplane.workflow.m0.model.M0RunStatus;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class M0RunApplicationServiceTest {

    private final InMemoryRunEventJournal journal = new InMemoryRunEventJournal();
    private final RunExecutionRegistry registry = new RunExecutionRegistry();

    @Test
    void returnsQueuedBeforeWorkerStartsAndThenProjectsTerminalResult() {
        AtomicReference<Runnable> task = new AtomicReference<>();
        TaskExecutor controlledExecutor = task::set;
        M0RunOrchestrator orchestrator = request -> failedResult(request.runId());
        M0RunApplicationService service = service(orchestrator, controlledExecutor);

        RunExecutionSnapshot queued = service.submit(command());

        assertThat(queued.status()).isEqualTo(RunExecutionStatus.QUEUED);
        assertThat(service.get(queued.runId()).status()).isEqualTo(RunExecutionStatus.QUEUED);
        assertThat(journal.types()).containsExactly(
                RunEventType.RUN_CREATED,
                RunEventType.RUN_QUEUED
        );

        task.get().run();

        RunExecutionSnapshot failed = service.get(queued.runId());
        assertThat(failed.status()).isEqualTo(RunExecutionStatus.FAILED);
        assertThat(failed.startedAt()).isNotNull();
        assertThat(failed.endedAt()).isNotNull();
        assertThat(failed.result().failureCode()).isEqualTo(M0RunFailureCode.WORKTREE_FAILED);
        assertThat(journal.types()).containsExactly(
                RunEventType.RUN_CREATED,
                RunEventType.RUN_QUEUED,
                RunEventType.RUN_STARTED
        );
    }

    @Test
    void rejectsSubmissionWhenExecutorQueueIsFull() {
        TaskExecutor rejectingExecutor = task -> {
            throw new TaskRejectedException("simulated full queue");
        };
        M0RunApplicationService service = service(request -> failedResult(request.runId()),
                rejectingExecutor);

        assertThatThrownBy(() -> service.submit(command()))
                .isInstanceOf(RunQueueFullException.class)
                .hasMessageContaining("队列已满");

        assertThat(journal.types()).containsExactly(
                RunEventType.RUN_CREATED,
                RunEventType.RUN_QUEUED,
                RunEventType.RUN_FAILED
        );
    }

    @Test
    void mapsUnexpectedWorkerFailureToInfrastructureFailure() {
        TaskExecutor directExecutor = Runnable::run;
        M0RunApplicationService service = service(request -> {
            throw new IllegalStateException("simulated infrastructure failure");
        }, directExecutor);

        RunExecutionSnapshot snapshot = service.submit(command());

        assertThat(service.get(snapshot.runId()).status())
                .isEqualTo(RunExecutionStatus.INFRASTRUCTURE_FAILED);
        assertThat(journal.types()).endsWith(
                RunEventType.RUN_STARTED,
                RunEventType.RUN_FAILED
        );
    }

    @Test
    void readsEventsStrictlyAfterRequestedSequence() {
        AtomicReference<Runnable> task = new AtomicReference<>();
        M0RunApplicationService service = service(
                request -> failedResult(request.runId()), task::set
        );
        UUID runId = service.submit(command()).runId();

        assertThat(service.events(runId, 1))
                .extracting(RunEvent::type)
                .containsExactly(RunEventType.RUN_QUEUED);
    }

    private M0RunApplicationService service(
            M0RunOrchestrator orchestrator,
            TaskExecutor executor
    ) {
        return new M0RunApplicationService(
                orchestrator,
                journal,
                registry,
                executor,
                JsonMapper.builder().findAndAddModules().build()
        );
    }

    private SubmitM0RunCommand command() {
        return new SubmitM0RunCommand(
                Path.of("D:/repository"),
                "HEAD",
                "修复示例问题",
                List.of("测试通过"),
                new ChangePolicy(List.of("src/**"), List.of(".git/**"))
        );
    }

    private M0RunResult failedResult(UUID runId) {
        Instant now = Instant.now();
        return new M0RunResult(
                runId,
                M0RunStatus.FAILED,
                M0RunStage.WORKTREE,
                M0RunFailureCode.WORKTREE_FAILED,
                null,
                null,
                null,
                null,
                null,
                now,
                now,
                "无法创建 Worktree。",
                "simulated"
        );
    }

    private static final class InMemoryRunEventJournal implements RunEventJournal {
        private final List<RunEvent> events = new ArrayList<>();

        @Override
        public synchronized RunEvent append(
                String runId,
                String stage,
                int attempt,
                RunEventType type,
                JsonNode payload
        ) {
            RunEvent event = new RunEvent(
                    RunEvent.CURRENT_SCHEMA_VERSION,
                    UUID.randomUUID(),
                    runId,
                    stage,
                    attempt,
                    events.size() + 1L,
                    Instant.now(),
                    type,
                    payload
            );
            events.add(event);
            return event;
        }

        @Override
        public synchronized List<RunEvent> readAfter(String runId, long afterSequence) {
            return events.stream()
                    .filter(event -> event.runId().equals(runId))
                    .filter(event -> event.sequence() > afterSequence)
                    .toList();
        }

        synchronized List<RunEventType> types() {
            return events.stream().map(RunEvent::type).toList();
        }
    }
}
