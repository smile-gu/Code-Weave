package com.codingreview.controlplane.application.run;

import com.codingreview.controlplane.application.run.error.RunNotFoundException;
import com.codingreview.controlplane.application.run.error.RunOutputUnavailableException;
import com.codingreview.controlplane.application.run.error.RunOutputUnavailableReason;
import com.codingreview.controlplane.application.run.error.RunQueueFullException;
import com.codingreview.controlplane.observability.run.RunEvent;
import com.codingreview.controlplane.observability.run.RunEventJournal;
import com.codingreview.controlplane.observability.run.RunEventType;
import com.codingreview.controlplane.workflow.m0.M0RunOrchestrator;
import com.codingreview.controlplane.workflow.m0.model.M0RunRequest;
import com.codingreview.controlplane.workflow.m0.model.M0RunResult;
import com.codingreview.controlplane.workspace.change.model.GitChangeSet;
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

    /** 创建 Run、记录排队事实并提交异步执行。 */
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

    /** 查询当前进程内指定 Run 的状态快照。 */
    public RunExecutionSnapshot get(UUID runId) {
        RunExecutionSnapshot snapshot = registry.find(runId);
        if (snapshot == null) {
            throw new RunNotFoundException(runId);
        }
        return snapshot;
    }

    /** 从事实日志中读取指定序号之后的 Run 事件。 */
    public List<RunEvent> events(UUID runId, long afterSequence) {
        get(runId);
        return eventJournal.readAfter(runId.toString(), afterSequence);
    }

    /** 读取已结束 Run 的业务执行结果。 */
    public M0RunResult result(UUID runId) {
        RunExecutionSnapshot snapshot = get(runId);
        if (!snapshot.terminal()) {
            throw unavailable(runId, RunOutputUnavailableReason.RUN_NOT_FINISHED);
        }
        if (snapshot.result() == null) {
            throw unavailable(runId, RunOutputUnavailableReason.RESULT_UNAVAILABLE);
        }
        return snapshot.result();
    }

    /** 读取已结束 Run 产生的代码变更快照。 */
    public GitChangeSet changes(UUID runId) {
        M0RunResult result = result(runId);
        if (result.changes() == null) {
            throw unavailable(runId, RunOutputUnavailableReason.CHANGES_UNAVAILABLE);
        }
        return result.changes();
    }

    /** 在线程池工作线程中执行 M0 主工作流并更新终态。 */
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

    /** 写入包含本次任务输入摘要的 Run 创建事件。 */
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

    /** 在队列拒绝任务时尽力写入基础设施失败事件。 */
    private void appendQueueRejected(UUID runId, String message, RuntimeException exception) {
        try {
            appendFailure(runId, "RUN_QUEUE_FULL", message, exception);
        } catch (RuntimeException journalFailure) {
            exception.addSuppressed(journalFailure);
        }
    }

    /** 在工作线程异常时尽力写入基础设施失败事件。 */
    private void appendUnexpectedFailure(UUID runId, String message, RuntimeException exception) {
        try {
            appendFailure(runId, "RUN_INFRASTRUCTURE_FAILED", message, exception);
        } catch (RuntimeException journalFailure) {
            exception.addSuppressed(journalFailure);
        }
    }

    /** 组装并写入统一的基础设施失败事件。 */
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

    /** 向 Run 级事件流追加一条事件。 */
    private RunEvent append(UUID runId, RunEventType type, ObjectNode payload) {
        return eventJournal.append(
                runId.toString(), "run", FIRST_ATTEMPT, type, payload
        );
    }

    /** 创建携带稳定失败原因的结果不可用异常。 */
    private RunOutputUnavailableException unavailable(
            UUID runId,
            RunOutputUnavailableReason reason
    ) {
        return new RunOutputUnavailableException(runId, reason);
    }
}
