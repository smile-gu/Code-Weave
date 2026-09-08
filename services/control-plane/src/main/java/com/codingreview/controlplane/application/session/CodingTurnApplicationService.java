package com.codingreview.controlplane.application.session;

import com.codingreview.controlplane.observability.run.RunEvent;
import com.codingreview.controlplane.application.session.error.CodingSessionConflictException;
import com.codingreview.controlplane.runtime.readiness.RuntimeReadinessService;
import com.codingreview.controlplane.runtime.readiness.error.RuntimeNotReadyException;
import com.codingreview.controlplane.observability.run.RunEventJournal;
import com.codingreview.controlplane.observability.run.RunEventType;
import com.codingreview.controlplane.session.model.CodingSession;
import com.codingreview.controlplane.session.model.CodingSessionStatus;
import com.codingreview.controlplane.session.model.CodingTurn;
import com.codingreview.controlplane.session.model.TurnStatus;
import com.codingreview.controlplane.session.persistence.CodingSessionRepository;
import com.codingreview.controlplane.session.persistence.CodingTurnRepository;
import com.codingreview.controlplane.session.guard.SessionWorkspaceDriftException;
import com.codingreview.controlplane.session.guard.SessionWorkspaceGuard;
import com.codingreview.controlplane.workflow.coding.CodingTurnExecution;
import com.codingreview.controlplane.workflow.coding.CodingTurnOrchestrator;
import com.codingreview.controlplane.workspace.change.GitChangeCollector;
import com.codingreview.controlplane.workspace.change.model.GitChangeSet;
import com.codingreview.controlplane.workspace.model.WorkspaceHandle;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;

/** 负责多轮 Turn 的创建、串行执行、停止和持久查询。 */
@Service
public final class CodingTurnApplicationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(CodingTurnApplicationService.class);
    private final CodingSessionRepository sessions;
    private final CodingTurnRepository turns;
    private final CodingTurnOrchestrator orchestrator;
    private final GitChangeCollector changeCollector;
    private final RunEventJournal journal;
    private final ObjectMapper mapper;
    private final TaskExecutor executor;
    private final RuntimeReadinessService readinessService;
    private final SessionWorkspaceGuard workspaceGuard;
    private final SessionOperationLockManager operationLocks;
    private final ConcurrentHashMap<UUID, ExecutionHandle> running = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> executionBlocks = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> resourceBlocks = ConcurrentHashMap.newKeySet();

    /** 装配 Turn 应用层及复用的执行资源和工作区门禁。 */
    public CodingTurnApplicationService(CodingSessionRepository sessions, CodingTurnRepository turns,
                                        CodingTurnOrchestrator orchestrator, GitChangeCollector changeCollector,
                                        RunEventJournal journal, ObjectMapper mapper,
                                        @Qualifier("codingTurnExecutor") TaskExecutor executor,
                                        RuntimeReadinessService readinessService,
                                        SessionWorkspaceGuard workspaceGuard,
                                        SessionOperationLockManager operationLocks) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.turns = Objects.requireNonNull(turns, "turns");
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        this.changeCollector = Objects.requireNonNull(changeCollector, "changeCollector");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.mapper = mapper.copy();
        this.executor = Objects.requireNonNull(executor, "executor");
        this.readinessService = Objects.requireNonNull(
                readinessService, "readinessService"
        );
        this.workspaceGuard = Objects.requireNonNull(workspaceGuard, "workspaceGuard");
        this.operationLocks = Objects.requireNonNull(operationLocks, "operationLocks");
    }

    /** 创建排队 Turn，并保证同一 Session 同时只有一轮运行。 */
    public CodingTurn submit(UUID sessionId, String prompt) {
        synchronized (lock(sessionId)) {
            CodingSession session = requireActive(sessionId);
            String executionBlock = executionBlocks.get(sessionId);
            if (executionBlock != null || resourceBlocks.contains(sessionId)) {
                throw new CodingSessionConflictException(
                        "SESSION_EXECUTION_BLOCKED",
                        "当前 Session 的上一次执行状态仍无法确认，请先处理后再继续：" + executionBlock
                );
            }
            try {
                workspaceGuard.assertStable(session);
            } catch (SessionWorkspaceDriftException exception) {
                throw new CodingSessionConflictException(exception.code(), exception.getMessage());
            }
            var readiness = readinessService.check();
            if (!readiness.ready()) {
                throw new RuntimeNotReadyException(readiness);
            }
            List<CodingTurn> history = turns.findAll(sessionId);
            if (history.stream().anyMatch(turn -> "PROCESS_CLEANUP_FAILED".equals(turn.failureCode()))) {
                resourceBlocks.add(sessionId);
                throw new CodingSessionConflictException("SESSION_EXECUTION_BLOCKED", "历史任务资源尚未确认清理，当前会话保持执行保护。");
            }
            if (running.containsKey(sessionId) || history.stream().anyMatch(this::active)) {
                throw new CodingSessionConflictException(
                        "TURN_ALREADY_RUNNING",
                        "当前 Session 已有正在执行的 Coding Turn，请等待其完成。"
                );
            }
            int sequence = history.stream().mapToInt(CodingTurn::sequence).max().orElse(0) + 1;
            CodingTurn queued = CodingTurn.queued(
                    UUID.randomUUID(), sessionId, sequence, prompt, Instant.now()
            );
            try {
                turns.save(queued);
            } catch (RuntimeException exception) {
                try {
                    var persisted = turns.find(sessionId, queued.turnId());
                    if (persisted.isPresent()) {
                        return failBeforeStart(persisted.get(), "TURN_QUEUE_STATE_WRITE_FAILED",
                                "任务排队状态未能完整确认，本轮未启动。", exception);
                    }
                } catch (RuntimeException readFailure) {
                    readFailure.addSuppressed(exception);
                    throw uncertainPersistence(sessionId, "排队状态无法确认", readFailure);
                }
                throw exception;
            }
            try {
                append(queued, RunEventType.RUN_QUEUED, queued.userMessage());
            } catch (RuntimeException exception) {
                return failBeforeStart(queued, "TURN_QUEUE_EVENT_WRITE_FAILED",
                        "任务排队事实无法可靠记录，本轮未启动。", exception);
            }
            AtomicBoolean cancellationRequested = new AtomicBoolean();
            FutureTask<Void> task = new FutureTask<>(() -> {
                execute(sessionId, queued.turnId(), cancellationRequested);
                return null;
            });
            ExecutionHandle handle = new ExecutionHandle(queued.turnId(), task, cancellationRequested);
            running.put(sessionId, handle);
            try {
                executor.execute(task);
            } catch (RuntimeException exception) {
                running.remove(sessionId, handle);
                return failBeforeStart(queued, "TURN_QUEUE_REJECTED",
                        "当前执行队列无法接收任务，本轮未启动。", exception);
            }
            return queued;
        }
    }

    /** 查询 Session 的全部 Turn。 */
    public List<CodingTurn> list(UUID sessionId) { requireSession(sessionId); return turns.findAll(sessionId); }

    /** 查询指定 Turn。 */
    public CodingTurn get(UUID sessionId, UUID turnId) { requireSession(sessionId); return turns.find(sessionId, turnId).orElseThrow(() -> new IllegalArgumentException("Coding Turn 不存在。")); }

    /** 查询 Turn 的持久事件。 */
    public List<RunEvent> events(UUID sessionId, UUID turnId, long afterSequence) { get(sessionId, turnId); return journal.readAfter(turnId.toString(), afterSequence); }

    /** 读取当前真实工作区相对 Session 基线的累计 Diff。 */
    public GitChangeSet changes(UUID sessionId) {
        synchronized (lock(sessionId)) {
            CodingSession session = requireSession(sessionId);
            List<CodingTurn> history = turns.findAll(sessionId);
            if (running.containsKey(sessionId) || history.stream().anyMatch(this::active)) {
                throw new CodingSessionConflictException(
                        "TURN_NOT_FINISHED", "当前 Turn 尚未结束，代码变更尚未稳定。"
                );
            }
            try {
                workspaceGuard.assertReadable(session);
            } catch (SessionWorkspaceDriftException exception) {
                throw new CodingSessionConflictException(exception.code(), exception.getMessage());
            }
            return changeCollector.collect(workspace(session));
        }
    }

    /** 兼容旧版按 Turn 查询累计 Diff 的入口。 */
    public GitChangeSet changes(UUID sessionId, UUID turnId) {
        get(sessionId, turnId);
        return changes(sessionId);
    }

    /** 请求停止当前 Turn；底层会中断 Claude 进程树。 */
    public CodingTurn cancel(UUID sessionId, UUID turnId) {
        synchronized (lock(sessionId)) {
            CodingTurn turn = get(sessionId, turnId);
            ExecutionHandle handle = running.get(sessionId);
            if (turn.status() == TurnStatus.QUEUED) {
                CodingTurn cancelled = turn.cancelled("本轮 Coding 已取消。", Instant.now());
                try {
                    turns.save(cancelled);
                } catch (RuntimeException exception) {
                    CodingTurn persisted = get(sessionId, turnId);
                    if (!persisted.equals(cancelled)) throw exception;
                    cancelled = persisted;
                }
                if (handle != null && handle.turnId().equals(turnId)) {
                    handle.cancellationRequested().set(true);
                    handle.task().cancel(true);
                    running.remove(sessionId, handle);
                }
                appendTerminalBestEffort(cancelled);
                return cancelled;
            }
            if (turn.status() == TurnStatus.RUNNING
                    && handle != null && handle.turnId().equals(turnId)) {
                handle.cancellationRequested().set(true);
                handle.task().cancel(true);
            }
            return turn;
        }
    }

    /** 在线程池中执行 Turn，并依次保存 Session 与 Turn，文件间不构成原子事务。 */
    private void execute(UUID sessionId, UUID turnId, AtomicBoolean cancellationRequested) {
        CodingTurn runningTurn;
        boolean orchestrationStarted = false;
        try {
            synchronized (lock(sessionId)) {
                CodingTurn current = get(sessionId, turnId);
                if (current.status() != TurnStatus.QUEUED) {
                    removeExecution(sessionId, turnId);
                    return;
                }
                runningTurn = current.running(Instant.now());
                try {
                    turns.save(runningTurn);
                } catch (RuntimeException exception) {
                    settleStartStateFailure(sessionId, current, exception);
                    return;
                }
            }
            CodingSession activeSession = requireActive(sessionId);
            orchestrationStarted = true;
            CodingTurnExecution result = orchestrator.execute(
                    activeSession, runningTurn, sessions::save,
                    cancellationRequested::get
            );
            settleWithoutInterrupt(sessionId, turnId, result, cancellationRequested);
        } catch (RuntimeException exception) {
            settleInfrastructureFailure(sessionId, turnId, exception, orchestrationStarted);
        } finally {
            removeExecution(sessionId, turnId);
        }
    }

    /** 重新读取启动状态写入结果，并在不启动 Claude 的前提下保存明确失败。 */
    private void settleStartStateFailure(
            UUID sessionId,
            CodingTurn queued,
            RuntimeException cause
    ) {
        final CodingTurn persisted;
        try {
            persisted = turns.find(sessionId, queued.turnId()).orElseThrow(() ->
                    uncertainPersistence(sessionId, "启动状态写入后 Turn 已无法定位", cause));
        } catch (RuntimeException readFailure) {
            executionBlocks.put(sessionId, "启动状态写入后无法重新核实");
            throw readFailure;
        }
        if (!active(persisted)) return;
        CodingTurn failed = persisted.status() == TurnStatus.QUEUED
                ? persisted.failBeforeStart(
                "TURN_START_STATE_WRITE_FAILED",
                "任务启动状态无法可靠保存，Claude Code 未启动。", Instant.now())
                : persisted.failed(
                com.codingreview.controlplane.session.model.TurnOutcome.AGENT_FAILED,
                "TURN_START_STATE_WRITE_FAILED",
                "任务启动状态写入异常，Claude Code 未启动。",
                null, null, Instant.now());
        persistTerminalOrBlock(sessionId, failed, cause);
    }

    /** 清除中断标志后执行有界持久化收敛，避免文件通道被线程中断跳过。 */
    private void settleWithoutInterrupt(
            UUID sessionId,
            UUID turnId,
            CodingTurnExecution candidate,
            AtomicBoolean cancellationRequested
    ) {
        boolean interrupted = Thread.interrupted();
        try {
            synchronized (lock(sessionId)) {
                interrupted |= Thread.interrupted();
                settleTerminal(sessionId, turnId, candidate, cancellationRequested.get());
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** 在 Session 锁内以最新持久状态裁决终态，并在落盘后尽力发布终态事件。 */
    private CodingTurn settleTerminal(
            UUID sessionId,
            UUID turnId,
            CodingTurnExecution candidate,
            boolean cancellationRequested
    ) {
        synchronized (lock(sessionId)) {
            if ("PROCESS_CLEANUP_FAILED".equals(candidate.turn().failureCode())) resourceBlocks.add(sessionId);
            CodingTurn latest = get(sessionId, turnId);
            if (!active(latest)) {
                return latest;
            }
            if ("PROCESS_CLEANUP_FAILED".equals(candidate.turn().failureCode())) {
                resourceBlocks.add(sessionId);
                try {
                    CodingSession protectedSession = requireSession(sessionId).fail(
                            "PROCESS_CLEANUP_FAILED", "进程资源未能确认清理，当前会话保持执行保护。", Instant.now());
                    try {
                        sessions.save(protectedSession);
                    } catch (RuntimeException writeFailure) {
                        if (!requireSession(sessionId).equals(protectedSession)) throw writeFailure;
                    }
                } catch (RuntimeException protectionFailure) {
                    LOGGER.error("资源保护快照保存未确认，保留内存阻断并将清理事实写入 Turn，sessionId={}",
                            sessionId, protectionFailure);
                }
                return persistTerminalOrBlock(sessionId, candidate.turn(), null);
            }
            CodingSession latestSession = requireSession(sessionId);
            CodingSession candidateSession = candidate.session();
            if (!Objects.equals(latestSession.claudeSessionId(), candidateSession.claudeSessionId())) {
                try {
                    sessions.save(candidateSession);
                } catch (RuntimeException exception) {
                    try {
                        CodingSession persistedSession = requireSession(sessionId);
                        if (persistedSession.equals(candidateSession)) {
                            latestSession = persistedSession;
                        } else {
                            return persistSessionStateFailure(latest, candidate, exception);
                        }
                    } catch (RuntimeException readFailure) {
                        readFailure.addSuppressed(exception);
                        return persistSessionStateFailure(latest, candidate, readFailure);
                    }
                }
                if (!latestSession.equals(candidateSession)) latestSession = candidateSession;
            }
            CodingTurn terminal = candidate.turn();
            if (cancellationRequested && terminal.status() == TurnStatus.COMPLETED) {
                terminal = latest.cancelled("本轮 Coding 已取消，真实工作区修改已保留。", Instant.now());
            }
            touchSessionBestEffort(latestSession);
            return persistTerminalOrBlock(sessionId, terminal, null);
        }
    }

    /** 尽力刷新 Session 的展示时间，失败不改变已经形成的业务候选结论。 */
    private void touchSessionBestEffort(CodingSession session) {
        try {
            sessions.save(session.touch(Instant.now()));
        } catch (RuntimeException exception) {
            LOGGER.warn("Session 最近活动时间刷新失败，不影响 Turn 业务结论，sessionId={}",
                    session.sessionId(), exception);
        }
    }

    /** 将无法核实或确实失败的必要 Session 更新收敛为 Turn 失败。 */
    private CodingTurn persistSessionStateFailure(
            CodingTurn latest,
            CodingTurnExecution candidate,
            RuntimeException cause
    ) {
        return persistTerminalOrBlock(latest.sessionId(), latest.failed(
                com.codingreview.controlplane.session.model.TurnOutcome.AGENT_FAILED,
                "SESSION_STATE_WRITE_FAILED",
                "Claude 会话上下文无法可靠保存，本轮不能确认成功。",
                candidate.turn().assistantMessage(), candidate.turn().nativeClaudeSessionId(),
                Instant.now()), cause);
    }

    /** 保存基础设施失败；若连终态也无法确认，则保留进程内执行阻断。 */
    private void settleInfrastructureFailure(UUID sessionId, UUID turnId, RuntimeException cause, boolean orchestrationStarted) {
        boolean interrupted = Thread.interrupted();
        try {
            synchronized (lock(sessionId)) {
                interrupted |= Thread.interrupted();
                CodingTurn latest = get(sessionId, turnId);
                if (!active(latest)) return;
                CodingTurn failed = latest.status() == TurnStatus.QUEUED
                        ? latest.failBeforeStart("TURN_INFRASTRUCTURE_FAILED",
                        "Turn 基础设施发生故障，任务尚未启动。", Instant.now())
                        : latest.failed(
                        com.codingreview.controlplane.session.model.TurnOutcome.AGENT_FAILED,
                        resourceBlocks.contains(sessionId) ? "PROCESS_CLEANUP_FAILED"
                                : orchestrationStarted ? "TURN_INFRASTRUCTURE_FAILED" : "TURN_START_FAILED",
                        "Turn 基础设施发生故障，真实工作区修改已保留。",
                        null, null, Instant.now());
                persistTerminalOrBlock(sessionId, failed, cause);
            }
        } catch (RuntimeException settlementFailure) {
            executionBlocks.put(sessionId, "终态保存失败：" + settlementFailure.getMessage());
            LOGGER.error("Turn 基础设施失败后仍无法完成终态收敛，sessionId={}, turnId={}",
                    sessionId, turnId, settlementFailure);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** 保存一个确定的终态并尽力追加通知事件。 */
    private CodingTurn persistTerminalOrBlock(UUID sessionId, CodingTurn terminal, RuntimeException cause) {
        try {
            turns.save(terminal);
        } catch (RuntimeException exception) {
            try {
                var persisted = turns.find(sessionId, terminal.turnId());
                if (persisted.isPresent() && persisted.get().equals(terminal)) {
                    appendTerminalBestEffort(persisted.get());
                    executionBlocks.remove(sessionId);
                    return persisted.get();
                }
            } catch (RuntimeException readFailure) {
                readFailure.addSuppressed(exception);
                executionBlocks.put(sessionId, "Turn 终态写入后无法重新核实");
                throw readFailure;
            }
            executionBlocks.put(sessionId, "Turn 终态无法可靠保存");
            if (cause != null) exception.addSuppressed(cause);
            throw exception;
        }
        executionBlocks.remove(sessionId);
        CodingTurn persisted;
        try {
            persisted = turns.find(sessionId, terminal.turnId()).orElseThrow(() ->
                    new IllegalStateException("Turn 终态保存后无法重新读取。"));
        } catch (RuntimeException exception) {
            executionBlocks.put(sessionId, "Turn 终态保存后无法重新核实");
            throw exception;
        }
        if (!persisted.equals(terminal)) {
            executionBlocks.put(sessionId, "Turn 终态保存结果与候选不一致");
            throw new IllegalStateException("Turn 终态保存结果与候选不一致。");
        }
        appendTerminalBestEffort(persisted);
        return persisted;
    }

    /** 将尚未启动的排队 Turn 收敛为失败，并避免再次依赖已故障的过程日志。 */
    private CodingTurn failBeforeStart(
            CodingTurn queued,
            String code,
            String message,
            RuntimeException cause
    ) {
        CodingTurn failed = queued.failBeforeStart(code, message, Instant.now());
        try {
            turns.save(failed);
        } catch (RuntimeException exception) {
            try {
                var persisted = turns.find(queued.sessionId(), queued.turnId());
                if (persisted.isPresent() && persisted.get().equals(failed)) {
                    executionBlocks.remove(queued.sessionId());
                    appendTerminalBestEffort(persisted.get());
                    return persisted.get();
                }
            } catch (RuntimeException readFailure) {
                exception.addSuppressed(readFailure);
            }
            executionBlocks.put(queued.sessionId(), "排队失败后的终态无法可靠保存");
            exception.addSuppressed(cause);
            throw exception;
        }
        executionBlocks.remove(queued.sessionId());
        appendTerminalBestEffort(failed);
        return failed;
    }

    /** 将无法核实的持久化结果转为服务错误并阻止同一 Session 继续执行。 */
    private RuntimeException uncertainPersistence(UUID sessionId, String message, RuntimeException cause) {
        executionBlocks.put(sessionId, message);
        return new IllegalStateException(message + "，当前 Session 已进入执行保护。", cause);
    }

    /** 尽力发布终态事件；失败仅记录诊断，由持久状态查询兜底。 */
    private void appendTerminalBestEffort(CodingTurn turn) {
        if ((turn.startedAt() == null || "TURN_START_STATE_WRITE_FAILED".equals(turn.failureCode())
                || "TURN_START_FAILED".equals(turn.failureCode()))
                && !"TURN_QUEUE_EVENT_WRITE_FAILED".equals(turn.failureCode())) {
            try {
                new com.codingreview.controlplane.workflow.coding.stage.CodingStageLifecycle(
                        turn.turnId().toString(), journal, mapper).skipRemaining(
                        turn.status() == TurnStatus.CANCELLED ? "TURN_CANCELLED" : "TURN_NOT_STARTED",
                        "本轮未进入执行阶段。", null);
            } catch (RuntimeException exception) {
                LOGGER.warn("启动前跳过事实未能完整记录，保留已保存终态，turnId={}", turn.turnId(), exception);
            }
        }
        try {
            appendTerminal(turn);
        } catch (RuntimeException exception) {
            LOGGER.error("Turn 终态已保存，但终态事件追加失败，sessionId={}, turnId={}, status={}",
                    turn.sessionId(), turn.turnId(), turn.status(), exception);
        }
    }

    /** 根据已保存的 Turn 业务结论选择唯一的终态事件。 */
    private void appendTerminal(CodingTurn turn) {
        RunEventType type;
        if (turn.status() == TurnStatus.CANCELLED) {
            type = RunEventType.RUN_CANCELLED;
        } else if (turn.status() == TurnStatus.FAILED || turn.status() == TurnStatus.INTERRUPTED
                || turn.outcome() == com.codingreview.controlplane.session.model.TurnOutcome.VERIFICATION_FAILED
                || turn.outcome() == com.codingreview.controlplane.session.model.TurnOutcome.POLICY_VIOLATION) {
            type = RunEventType.RUN_FAILED;
        } else {
            type = RunEventType.RUN_COMPLETED;
        }
        append(turn, type, turn.userMessage());
    }

    /** 获取必须存在的 Session。 */
    private CodingSession requireSession(UUID id) { return sessions.find(id).orElseThrow(() -> new IllegalArgumentException("Coding Session 不存在。")); }
    /** 当前 Session 使用真实仓库，历史只读 Session 保留原 Worktree 路径。 */
    private WorkspaceHandle workspace(CodingSession session) {
        return new WorkspaceHandle(
                session.usesRealRepository()
                        ? session.sourceRepository() : session.worktreePath(),
                session.activeReviewBaselineCommit()
        );
    }
    /** 获取可继续 Coding 的 Session。 */
    private CodingSession requireActive(UUID id) {
        CodingSession session = requireSession(id);
        if (session.status() != CodingSessionStatus.ACTIVE) {
            throw new CodingSessionConflictException(
                    "SESSION_NOT_ACTIVE",
                    "当前 Session 不允许继续 Coding：" + session.status()
            );
        }
        return session;
    }
    /** 判断 Turn 是否正在占用 Session 的串行执行权。 */
    private boolean active(CodingTurn turn) { return turn.status() == TurnStatus.QUEUED || turn.status() == TurnStatus.RUNNING; }
    /** 返回指定 Session 的进程内互斥锁。 */
    private Object lock(UUID sessionId) { return operationLocks.lock(sessionId); }
    /** 仅移除属于当前 Turn 的执行句柄，避免覆盖刚提交的下一轮。 */
    private void removeExecution(UUID sessionId, UUID turnId) {
        running.computeIfPresent(sessionId,
                (ignored, handle) -> handle.turnId().equals(turnId) ? null : handle);
    }
    /** 追加 Turn 级事件。 */
    private void append(CodingTurn turn, RunEventType type, String message) {
        journal.append(turn.turnId().toString(), "coding-turn", 1, type, mapper.createObjectNode()
                .put("turnId", turn.turnId().toString()).put("message", message)
                .put("failedStage", turn.failedStage()).put("failureKind", turn.failureKind())
                .put("failureCode", turn.failureCode()));
    }

    /** 关联一个 Session 当前占用执行资源的 Turn 和 Future。 */
    private record ExecutionHandle(
            UUID turnId,
            FutureTask<Void> task,
            AtomicBoolean cancellationRequested
    ) { }
}
