package com.codingreview.controlplane.application.run;

import com.codingreview.controlplane.application.run.error.RunNotFoundException;
import com.codingreview.controlplane.application.run.error.RunQueueFullException;
import com.codingreview.controlplane.observability.run.RunEvent;
import com.codingreview.controlplane.observability.run.RunEventJournal;
import com.codingreview.controlplane.observability.run.RunEventType;
import com.codingreview.controlplane.workflow.m0.M0RunOrchestrator;
import com.codingreview.controlplane.workflow.m0.model.M0RunRequest;
import com.codingreview.controlplane.workflow.m0.model.M0RunResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 负责 Run 的创建、排队、异步执行和查询，不介入五阶段业务编排。 */
@Service
public class M0RunApplicationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(M0RunApplicationService.class);
    private static final int FIRST_ATTEMPT = 1;

    private final M0RunOrchestrator orchestrator;
    private final RunEventJournal eventJournal;
    private final RunExecutionRegistry registry;
    private final TaskExecutor executor;
    private final ObjectMapper objectMapper;

    public M0RunApplicationService(
            M0RunOrchestrator orchestrator,
            RunEventJournal eventJournal,
            RunExecutionRegistry registry,
            @Qualifier("m0RunExecutor") TaskExecutor executor,
            ObjectMapper objectMapper
    ) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        this.eventJournal = Objects.requireNonNull(eventJournal, "eventJournal");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
    }

    public RunExecutionSnapshot submit(SubmitM0RunCommand command) {
        Objects.requireNonNull(command, "command");
        UUID runId = UUID.randomUUID();
        M0RunRequest request = new M0RunRequest(
                runId,
                command.repository(),
                command.baseRef(),
                command.taskPrompt(),
                command.acceptanceCriteria(),
                command.changePolicy()
        );
        Instant createdAt = Instant.now();

        appendCreated(request);
        append(runId, RunEventType.RUN_QUEUED,
                objectMapper.createObjectNode().put("message", "任务已进入执行队列。"));
        RunExecutionSnapshot snapshot = registry.registerQueued(runId, createdAt);

        try {
            executor.execute(() -> execute(request));
        } catch (TaskRejectedException exception) {
            String message = "当前任务队列已满，任务未开始执行。";
            registry.markInfrastructureFailed(runId, Instant.now(), message);
            appendQueueRejected(runId, message, exception);
            LOGGER.warn("M0 Run 队列已满，runId={}", runId);
            throw new RunQueueFullException(runId, exception);
        }
        LOGGER.info("M0 Run 已提交，runId={}，repository={}", runId, request.repository());
        return snapshot;
    }

    public RunExecutionSnapshot get(UUID runId) {
        RunExecutionSnapshot snapshot = registry.find(runId);
        if (snapshot == null) {
            throw new RunNotFoundException(runId);
        }
        return snapshot;
    }

    public List<RunEvent> events(UUID runId, long afterSequence) {
        get(runId);
        return eventJournal.readAfter(runId.toString(), afterSequence);
    }

    private void execute(M0RunRequest request) {
        try {
            append(request.runId(), RunEventType.RUN_STARTED,
                    objectMapper.createObjectNode().put("message", "任务开始执行。"));
            registry.markRunning(request.runId(), Instant.now());
            LOGGER.info("M0 Run 开始执行，runId={}", request.runId());
            M0RunResult result = orchestrator.execute(request);
            registry.complete(request.runId(), result);
            LOGGER.info("M0 Run 执行结束，runId={}，status={}",
                    request.runId(), result.status());
        } catch (RuntimeException exception) {
            String message = "运行基础设施发生故障，任务已停止，请查看服务日志。";
            registry.markInfrastructureFailed(request.runId(), Instant.now(), message);
            appendUnexpectedFailure(request.runId(), message, exception);
            LOGGER.error("M0 Run 基础设施故障，runId={}", request.runId(), exception);
        }
    }

    private void appendCreated(M0RunRequest request) {
        ObjectNode payload = objectMapper.createObjectNode()
                .put("workflow", "M0")
                .put("repository", request.repository().toString())
                .put("baseRef", request.baseRef())
                .put("taskPrompt", request.taskPrompt());
        payload.set("acceptanceCriteria", objectMapper.valueToTree(request.acceptanceCriteria()));
        payload.set("allowedPaths", objectMapper.valueToTree(request.changePolicy().allowedPaths()));
        payload.set("deniedPaths", objectMapper.valueToTree(request.changePolicy().deniedPaths()));
        append(request.runId(), RunEventType.RUN_CREATED, payload);
    }

    private void appendQueueRejected(UUID runId, String message, RuntimeException exception) {
        try {
            appendFailure(runId, "RUN_QUEUE_FULL", message, exception);
        } catch (RuntimeException journalFailure) {
            exception.addSuppressed(journalFailure);
        }
    }

    private void appendUnexpectedFailure(UUID runId, String message, RuntimeException exception) {
        try {
            appendFailure(runId, "RUN_INFRASTRUCTURE_FAILED", message, exception);
        } catch (RuntimeException journalFailure) {
            exception.addSuppressed(journalFailure);
        }
    }

    private void appendFailure(
            UUID runId,
            String failureCode,
            String message,
            RuntimeException exception
    ) {
        append(runId, RunEventType.RUN_FAILED,
                objectMapper.createObjectNode()
                        .put("status", RunExecutionStatus.INFRASTRUCTURE_FAILED.name())
                        .put("failureCode", failureCode)
                        .put("message", message)
                        .put("diagnostic", exception.getClass().getSimpleName()
                                + ": " + exception.getMessage()));
    }

    private RunEvent append(UUID runId, RunEventType type, ObjectNode payload) {
        return eventJournal.append(
                runId.toString(), "run", FIRST_ATTEMPT, type, payload
        );
    }
}
