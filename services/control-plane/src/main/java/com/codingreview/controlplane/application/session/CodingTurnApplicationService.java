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
import com.codingreview.controlplane.workflow.m6.CodingTurnExecution;
import com.codingreview.controlplane.workflow.m6.CodingTurnOrchestrator;
import com.codingreview.controlplane.workflow.m6.CodingTurnOrchestratorImpl.CodingTurnExecutionException;
import com.codingreview.controlplane.workspace.change.GitChangeCollector;
import com.codingreview.controlplane.workspace.change.model.GitChangeSet;
import com.codingreview.controlplane.workspace.model.WorkspaceHandle;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;

/** 负责多轮 Turn 的创建、串行执行、停止和持久查询。 */
@Service
public final class CodingTurnApplicationService {
    private final CodingSessionRepository sessions;
    private final CodingTurnRepository turns;
    private final CodingTurnOrchestrator orchestrator;
    private final GitChangeCollector changeCollector;
    private final RunEventJournal journal;
    private final ObjectMapper mapper;
    private final TaskExecutor executor;
    private final RuntimeReadinessService readinessService;
    private final SessionWorkspaceGuard workspaceGuard;
    private final ConcurrentHashMap<UUID, ExecutionHandle> running = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Object> sessionLocks = new ConcurrentHashMap<>();

    /** 装配 Turn 应用层及复用的执行资源和工作区门禁。 */
    public CodingTurnApplicationService(CodingSessionRepository sessions, CodingTurnRepository turns,
                                        CodingTurnOrchestrator orchestrator, GitChangeCollector changeCollector,
                                        RunEventJournal journal, ObjectMapper mapper,
                                        @Qualifier("codingTurnExecutor") TaskExecutor executor,
                                        RuntimeReadinessService readinessService,
                                        SessionWorkspaceGuard workspaceGuard) {
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
    }

    /** 创建排队 Turn，并保证同一 Session 同时只有一轮运行。 */
    public CodingTurn submit(UUID sessionId, String prompt) {
        synchronized (lock(sessionId)) {
            CodingSession session = requireActive(sessionId);
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
            turns.save(queued);
            append(queued, RunEventType.RUN_QUEUED, queued.userMessage());
            FutureTask<Void> task = new FutureTask<>(() -> {
                execute(sessionId, queued.turnId());
                return null;
            });
            ExecutionHandle handle = new ExecutionHandle(queued.turnId(), task);
            running.put(sessionId, handle);
            try {
                executor.execute(task);
            } catch (RuntimeException exception) {
                running.remove(sessionId, handle);
                CodingTurn failed = queued.running(Instant.now()).failed(
                        com.codingreview.controlplane.session.model.TurnOutcome.AGENT_FAILED,
                        "TURN_QUEUE_REJECTED", "当前执行队列已满。",
                        null, null, Instant.now()
                );
                turns.save(failed);
                append(failed, RunEventType.RUN_FAILED, failed.userMessage());
                throw exception;
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
    public GitChangeSet changes(UUID sessionId, UUID turnId) {
        CodingTurn turn = get(sessionId, turnId);
        if (turn.status() == TurnStatus.QUEUED || turn.status() == TurnStatus.RUNNING) {
            throw new CodingSessionConflictException(
                    "TURN_NOT_FINISHED", "当前 Turn 尚未结束，代码变更尚未稳定。"
            );
        }
        CodingSession session = requireSession(sessionId);
        return changeCollector.collect(workspace(session));
    }

    /** 请求停止当前 Turn；底层会中断 Claude 进程树。 */
    public CodingTurn cancel(UUID sessionId, UUID turnId) {
        synchronized (lock(sessionId)) {
            CodingTurn turn = get(sessionId, turnId);
            ExecutionHandle handle = running.get(sessionId);
            if (turn.status() == TurnStatus.QUEUED) {
                if (handle != null && handle.turnId().equals(turnId)) {
                    handle.task().cancel(true);
                    running.remove(sessionId, handle);
                }
                CodingTurn cancelled = turn.cancelled("本轮 Coding 已取消。", Instant.now());
                turns.save(cancelled);
                append(cancelled, RunEventType.RUN_CANCELLED, cancelled.userMessage());
                return cancelled;
            }
            if (turn.status() == TurnStatus.RUNNING
                    && handle != null && handle.turnId().equals(turnId)) {
                handle.task().cancel(true);
            }
            return turn;
        }
    }

    /** 在线程池中执行 Turn 并将 Session 和 Turn 终态原子顺序落盘。 */
    private void execute(UUID sessionId, UUID turnId) {
        CodingTurn runningTurn;
        synchronized (lock(sessionId)) {
            CodingTurn current = get(sessionId, turnId);
            if (current.status() != TurnStatus.QUEUED) {
                removeExecution(sessionId, turnId);
                return;
            }
            runningTurn = current.running(Instant.now());
            turns.save(runningTurn);
        }
        try {
            CodingTurnExecution result = orchestrator.execute(
                    requireActive(sessionId), runningTurn, sessions::save
            );
            synchronized (lock(sessionId)) {
                sessions.save(result.session().touch(Instant.now()));
                turns.save(result.turn());
            }
        } catch (CodingTurnExecutionException exception) {
            synchronized (lock(sessionId)) {
                turns.save(exception.failedTurn());
            }
        } catch (RuntimeException exception) {
            CodingTurn failed = runningTurn.failed(
                    com.codingreview.controlplane.session.model.TurnOutcome.AGENT_FAILED,
                    "TURN_INFRASTRUCTURE_FAILED",
                    "Turn 基础设施发生故障，真实工作区修改已保留。",
                    null, null, Instant.now()
            );
            synchronized (lock(sessionId)) {
                turns.save(failed);
                append(failed, RunEventType.RUN_FAILED, failed.userMessage());
            }
        } finally {
            removeExecution(sessionId, turnId);
        }
    }

    /** 获取必须存在的 Session。 */
    private CodingSession requireSession(UUID id) { return sessions.find(id).orElseThrow(() -> new IllegalArgumentException("Coding Session 不存在。")); }
    /** 当前 Session 使用真实仓库，历史只读 Session 保留原 Worktree 路径。 */
    private WorkspaceHandle workspace(CodingSession session) {
        return new WorkspaceHandle(
                session.usesRealRepository()
                        ? session.sourceRepository() : session.worktreePath(),
                session.resolvedCommit()
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
    private Object lock(UUID sessionId) { return sessionLocks.computeIfAbsent(sessionId, ignored -> new Object()); }
    /** 仅移除属于当前 Turn 的执行句柄，避免覆盖刚提交的下一轮。 */
    private void removeExecution(UUID sessionId, UUID turnId) {
        running.computeIfPresent(sessionId,
                (ignored, handle) -> handle.turnId().equals(turnId) ? null : handle);
    }
    /** 追加 Turn 级事件。 */
    private void append(CodingTurn turn, RunEventType type, String message) { journal.append(turn.turnId().toString(), "coding-turn", 1, type, mapper.createObjectNode().put("turnId", turn.turnId().toString()).put("message", message)); }

    /** 关联一个 Session 当前占用执行资源的 Turn 和 Future。 */
    private record ExecutionHandle(UUID turnId, FutureTask<Void> task) { }
}
