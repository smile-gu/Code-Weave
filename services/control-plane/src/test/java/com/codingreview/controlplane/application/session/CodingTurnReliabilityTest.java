package com.codingreview.controlplane.application.session;

import com.codingreview.controlplane.application.session.error.CodingSessionConflictException;
import com.codingreview.controlplane.observability.run.RunEvent;
import com.codingreview.controlplane.observability.run.RunEventJournal;
import com.codingreview.controlplane.observability.run.RunEventType;
import com.codingreview.controlplane.runtime.readiness.model.RuntimeReadiness;
import com.codingreview.controlplane.session.guard.SessionWorkspaceGuard;
import com.codingreview.controlplane.session.model.CodingSession;
import com.codingreview.controlplane.session.model.CodingTurn;
import com.codingreview.controlplane.session.model.TurnOutcome;
import com.codingreview.controlplane.session.model.TurnStatus;
import com.codingreview.controlplane.session.persistence.CodingSessionRepository;
import com.codingreview.controlplane.session.persistence.CodingTurnRepository;
import com.codingreview.controlplane.workflow.coding.CodingTurnExecution;
import com.codingreview.controlplane.workflow.coding.CodingTurnOrchestrator;
import com.codingreview.controlplane.workspace.change.GitChangeCollector;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicy;
import com.codingreview.controlplane.workspace.git.model.GitRepositorySnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** 验证终态、排队、取消竞争和持久化故障均按统一规则收敛。 */
class CodingTurnReliabilityTest {

    @Test
    void keepsSavedTerminalWhenTerminalEventCannotBeAppended() {
        Fixture fixture = fixture(new CapturingExecutor(), completedOrchestrator());
        fixture.journal.failTerminal = true;

        CodingTurn queued = fixture.service.submit(fixture.session.sessionId(), "完成任务");
        fixture.executor.tasks.getFirst().run();

        CodingTurn stored = fixture.turns.find(fixture.session.sessionId(), queued.turnId()).orElseThrow();
        assertThat(stored.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(fixture.journal.types).contains(RunEventType.RUN_QUEUED);
    }

    @Test
    void returnsFailedTurnWithoutFakeStartWhenExecutorRejectsTask() {
        Fixture fixture = fixture(task -> { throw new IllegalStateException("rejected"); }, completedOrchestrator());

        CodingTurn failed = fixture.service.submit(fixture.session.sessionId(), "无法入队");

        assertThat(failed.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(failed.failureCode()).isEqualTo("TURN_QUEUE_REJECTED");
        assertThat(failed.startedAt()).isNull();
        assertThat(fixture.journal.types).containsExactly(
                RunEventType.RUN_QUEUED, RunEventType.RUN_FAILED
        );
    }

    @Test
    void verifiesQueueFailureAfterRepositoryReplacedFileThenThrew() {
        Fixture fixture = fixture(task -> { throw new IllegalStateException("rejected"); }, completedOrchestrator());
        fixture.turns.replaceThenFailTerminal = true;

        CodingTurn failed = fixture.service.submit(fixture.session.sessionId(), "写后异常的排队失败");

        assertThat(failed.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(failed.failureCode()).isEqualTo("TURN_QUEUE_REJECTED");
        assertThat(failed.startedAt()).isNull();
        assertThat(fixture.journal.types.stream().filter(type -> type == RunEventType.RUN_FAILED))
                .hasSize(1);
    }

    @Test
    void doesNotStartAgentWhenRunningStateCannotBeSaved() {
        CapturingExecutor executor = new CapturingExecutor();
        CodingTurnOrchestrator orchestrator = mock(CodingTurnOrchestrator.class);
        Fixture fixture = fixture(executor, orchestrator);
        fixture.turns.failRunningWrites = true;

        CodingTurn queued = fixture.service.submit(fixture.session.sessionId(), "启动状态失败");
        executor.tasks.getFirst().run();

        CodingTurn stored = fixture.turns.find(fixture.session.sessionId(), queued.turnId()).orElseThrow();
        assertThat(stored.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(stored.failureCode()).isEqualTo("TURN_START_STATE_WRITE_FAILED");
        assertThat(stored.startedAt()).isNull();
        org.mockito.Mockito.verifyNoInteractions(orchestrator);
    }

    @Test
    void verifiesRunningStateAfterRepositoryReplacedFileThenThrew() {
        CapturingExecutor executor = new CapturingExecutor();
        CodingTurnOrchestrator orchestrator = mock(CodingTurnOrchestrator.class);
        Fixture fixture = fixture(executor, orchestrator);
        fixture.turns.replaceThenFailRunning = true;

        CodingTurn queued = fixture.service.submit(fixture.session.sessionId(), "写后异常");
        executor.tasks.getFirst().run();

        CodingTurn stored = fixture.turns.find(fixture.session.sessionId(), queued.turnId()).orElseThrow();
        assertThat(stored.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(stored.failureCode()).isEqualTo("TURN_START_STATE_WRITE_FAILED");
        assertThat(stored.startedAt()).isNotNull();
        org.mockito.Mockito.verifyNoInteractions(orchestrator);
    }

    @Test
    void refusesSuccessWhenRequiredClaudeSessionUpdateCannotBeSaved() {
        Fixture fixture = fixture(new CapturingExecutor(), new CodingTurnOrchestrator() {
            @Override
            public CodingTurnExecution execute(CodingSession session, CodingTurn turn,
                                               Consumer<CodingSession> sink) {
                CodingSession updated = session.bindClaudeSession("native-session", Instant.now());
                return new CodingTurnExecution(updated, completed(turn));
            }
        });
        fixture.sessions.failNativeSessionWrites = true;

        CodingTurn queued = fixture.service.submit(fixture.session.sessionId(), "继续上下文");
        fixture.executor.tasks.getFirst().run();

        CodingTurn stored = fixture.turns.find(fixture.session.sessionId(), queued.turnId()).orElseThrow();
        assertThat(stored.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(stored.failureCode()).isEqualTo("SESSION_STATE_WRITE_FAILED");
        assertThat(fixture.journal.types).doesNotContain(RunEventType.RUN_COMPLETED);
    }

    @Test
    void verifiesRequiredSessionUpdateAfterRepositoryReplacedFileThenThrew() {
        Fixture fixture = fixture(new CapturingExecutor(), new CodingTurnOrchestrator() {
            @Override
            public CodingTurnExecution execute(CodingSession session, CodingTurn turn,
                                               Consumer<CodingSession> sink) {
                CodingSession updated = session.bindClaudeSession("native-session", Instant.now());
                return new CodingTurnExecution(updated, completed(turn));
            }
        });
        fixture.sessions.replaceThenFailNativeSession = true;

        CodingTurn queued = fixture.service.submit(fixture.session.sessionId(), "核实已保存的上下文");
        fixture.executor.tasks.getFirst().run();

        CodingTurn stored = fixture.turns.find(fixture.session.sessionId(), queued.turnId()).orElseThrow();
        assertThat(stored.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(fixture.sessions.find(fixture.session.sessionId()).orElseThrow().claudeSessionId())
                .isEqualTo("native-session");
        assertThat(fixture.journal.types).contains(RunEventType.RUN_COMPLETED);
    }

    @Test
    void cancellationRegisteredBeforeCompletionWinsDeterministically() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch allowCompletion = new CountDownLatch(1);
        CodingTurnOrchestrator orchestrator = new CodingTurnOrchestrator() {
            @Override
            public CodingTurnExecution execute(CodingSession session, CodingTurn turn,
                                               Consumer<CodingSession> sink) {
                return new CodingTurnExecution(session, completed(turn));
            }

            @Override
            public CodingTurnExecution execute(CodingSession session, CodingTurn turn,
                                               Consumer<CodingSession> sink,
                                               BooleanSupplier cancellationRequested) {
                entered.countDown();
                try {
                    allowCompletion.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // 模拟底层已完成有界资源清理后仍返回普通完成候选。
                }
                return new CodingTurnExecution(session, completed(turn));
            }
        };
        Fixture fixture = fixture(task -> {
            Thread worker = new Thread(task, "turn-reliability-test");
            worker.setDaemon(true);
            worker.start();
        }, orchestrator);

        CodingTurn queued = fixture.service.submit(fixture.session.sessionId(), "可取消任务");
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        CodingTurn cancelResponse = fixture.service.cancel(fixture.session.sessionId(), queued.turnId());
        allowCompletion.countDown();
        assertThat(fixture.turns.terminalSaved.await(5, TimeUnit.SECONDS)).isTrue();

        CodingTurn stored = fixture.turns.find(fixture.session.sessionId(), queued.turnId()).orElseThrow();
        assertThat(cancelResponse.status()).isEqualTo(TurnStatus.RUNNING);
        assertThat(stored.status()).isEqualTo(TurnStatus.CANCELLED);
        assertThat(fixture.journal.types.stream().filter(type -> type == RunEventType.RUN_CANCELLED))
                .hasSize(1);
    }

    @Test
    void terminalPersistenceFailureBlocksTheNextTurn() {
        Fixture fixture = fixture(new CapturingExecutor(), completedOrchestrator());
        fixture.turns.failTerminalWrites = true;
        fixture.service.submit(fixture.session.sessionId(), "终态无法保存");
        fixture.executor.tasks.getFirst().run();

        assertThatThrownBy(() -> fixture.service.submit(fixture.session.sessionId(), "不得继续"))
                .isInstanceOf(CodingSessionConflictException.class)
                .satisfies(error -> assertThat(((CodingSessionConflictException) error).code())
                        .isEqualTo("SESSION_EXECUTION_BLOCKED"));
    }

    @Test
    void completionSavedBeforeCancellationRemainsCompletedWithoutDuplicateEvent() {
        Fixture fixture = fixture(new CapturingExecutor(), completedOrchestrator());
        CodingTurn queued = fixture.service.submit(fixture.session.sessionId(), "快速完成");
        fixture.executor.tasks.getFirst().run();

        CodingTurn response = fixture.service.cancel(fixture.session.sessionId(), queued.turnId());

        assertThat(response.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(fixture.journal.types.stream().filter(type -> type == RunEventType.RUN_COMPLETED))
                .hasSize(1);
        assertThat(fixture.journal.types).doesNotContain(RunEventType.RUN_CANCELLED);
    }

    @Test
    void repeatedQueuedCancellationKeepsOneTerminalEvent() {
        Fixture fixture = fixture(new CapturingExecutor(), completedOrchestrator());
        CodingTurn queued = fixture.service.submit(fixture.session.sessionId(), "仍在排队");

        CodingTurn first = fixture.service.cancel(fixture.session.sessionId(), queued.turnId());
        CodingTurn second = fixture.service.cancel(fixture.session.sessionId(), queued.turnId());

        assertThat(first.status()).isEqualTo(TurnStatus.CANCELLED);
        assertThat(second).isEqualTo(first);
        assertThat(fixture.journal.types.stream().filter(type -> type == RunEventType.RUN_CANCELLED))
                .hasSize(1);
    }

    @Test
    void processCleanupFailureFailsAndProtectsSession() {
        CodingTurnOrchestrator orchestrator = (session, turn, sink) -> new CodingTurnExecution(
                session,
                turn.failed(TurnOutcome.AGENT_FAILED, "PROCESS_CLEANUP_FAILED",
                        "进程清理失败", null, null, Instant.now())
        );
        Fixture fixture = fixture(new CapturingExecutor(), orchestrator);
        CodingTurn queued = fixture.service.submit(fixture.session.sessionId(), "触发清理失败");
        fixture.executor.tasks.getFirst().run();

        assertThat(fixture.turns.find(fixture.session.sessionId(), queued.turnId()).orElseThrow().failureCode())
                .isEqualTo("PROCESS_CLEANUP_FAILED");
        assertThat(fixture.sessions.find(fixture.session.sessionId()).orElseThrow().failureCode())
                .isEqualTo("PROCESS_CLEANUP_FAILED");
        assertThatThrownBy(() -> fixture.service.submit(fixture.session.sessionId(), "不得继续"))
                .isInstanceOf(CodingSessionConflictException.class);
    }

    /** 创建正常完成的受控编排器。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 2, 3})
    void cleanupRiskSurvivesProtectionWriteFailure(int mode) {
        Fixture fixture = fixture(new CapturingExecutor(), (session, turn, sink) ->
                new CodingTurnExecution(session, turn.failed(TurnOutcome.AGENT_FAILED,
                        "PROCESS_CLEANUP_FAILED", "清理不确定", null, null, Instant.now())));
        fixture.sessions.failProtectionWrites = true;
        fixture.sessions.replaceThenFailProtection = mode == 1;
        fixture.sessions.failProtectionRead = mode == 2;
        fixture.turns.failTerminalWrites = mode == 3;
        CodingTurn queued = fixture.service.submit(fixture.session.sessionId(), "清理失败");
        fixture.executor.tasks.getFirst().run();
        if (mode != 3) assertThat(fixture.service.get(fixture.session.sessionId(), queued.turnId()).failureCode())
                    .isEqualTo("PROCESS_CLEANUP_FAILED");
        assertThatThrownBy(() -> fixture.service.submit(fixture.session.sessionId(), "不得执行"))
                .isInstanceOf(CodingSessionConflictException.class);
    }

    @Test
    void cleanupEvidenceInTurnRestoresProtectionAcrossTwoRestarts() throws Exception {
        Fixture fixture = fixture(new CapturingExecutor(), (session, turn, sink) ->
                new CodingTurnExecution(session, turn.failed(TurnOutcome.AGENT_FAILED,
                        "PROCESS_CLEANUP_FAILED", "清理不确定", null, null, Instant.now())));
        fixture.sessions.failProtectionWrites = true;
        CodingTurn queued = fixture.service.submit(fixture.session.sessionId(), "保护写入失败");
        fixture.executor.tasks.getFirst().run();
        Path base = Path.of("target/reliability-restarts").toAbsolutePath();
        java.nio.file.Files.createDirectories(base);
        Path runtime = java.nio.file.Files.createTempDirectory(base, "run-");
        java.nio.file.Files.createDirectories(fixture.session.sourceRepository());
        var mapper = JsonMapper.builder().findAndAddModules().build();
        new com.codingreview.controlplane.session.persistence.FileCodingSessionRepository(runtime, mapper)
                .save(fixture.sessions.session);
        new com.codingreview.controlplane.session.persistence.FileCodingTurnRepository(runtime, mapper)
                .save(fixture.service.get(fixture.session.sessionId(), queued.turnId()));
        for (int restart = 0; restart < 2; restart++) {
            var sessions = new com.codingreview.controlplane.session.persistence.FileCodingSessionRepository(runtime, mapper);
            var turns = new com.codingreview.controlplane.session.persistence.FileCodingTurnRepository(runtime, mapper);
            try (var locks = new com.codingreview.controlplane.workspace.lock.FileRepositoryWriteLockManager(runtime)) {
                new CodingSessionRecoveryService(sessions, locks).run(null);
                new CodingTurnRecoveryService(sessions, turns, fixture.journal, mapper).run(null);
                assertThat(sessions.find(fixture.session.sessionId()).orElseThrow().failureCode())
                        .isEqualTo("PROCESS_CLEANUP_FAILED");
                assertThatThrownBy(() -> locks.acquire(fixture.session.sourceRepository(), UUID.randomUUID()))
                        .isInstanceOf(com.codingreview.controlplane.workspace.lock.RepositoryWriteLockException.class);
            }
        }
    }

    @Test
    void cancellationWhileWorkerWaitsForTerminalLockWins() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        Fixture fixture = fixture(new CapturingExecutor(), (session, turn, sink) -> {
            entered.countDown();
            try {
                if (!finish.await(5, TimeUnit.SECONDS)) throw new AssertionError("未释放编排器");
            } catch (InterruptedException exception) { throw new AssertionError(exception); }
            return new CodingTurnExecution(session, completed(turn));
        });
        CodingTurn queued = fixture.service.submit(fixture.session.sessionId(), "锁内取消");
        Thread worker = new Thread(fixture.executor.tasks.getFirst());
        worker.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        synchronized (fixture.locks.lock(fixture.session.sessionId())) {
            finish.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (worker.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(worker.getState()).isEqualTo(Thread.State.BLOCKED);
            fixture.service.cancel(fixture.session.sessionId(), queued.turnId());
        }
        worker.join(5000);
        assertThat(worker.isAlive()).isFalse();
        assertThat(fixture.service.get(fixture.session.sessionId(), queued.turnId()).status())
                .isEqualTo(TurnStatus.CANCELLED);
    }

    /** 创建正常完成的受控编排器。 */
    private CodingTurnOrchestrator completedOrchestrator() {
        return (session, turn, sink) -> new CodingTurnExecution(session, completed(turn));
    }

    /** 从运行中 Turn 构造一个无代码变更的完成候选。 */
    private static CodingTurn completed(CodingTurn turn) {
        return turn.complete(TurnOutcome.NO_CHANGES, 0, null,
                "执行完成", "完成", null, Instant.now());
    }

    /** 装配带真实内存状态的可靠性测试夹具。 */
    private Fixture fixture(TaskExecutor executor, CodingTurnOrchestrator orchestrator) {
        CodingSession session = activeSession();
        MemorySessions sessions = new MemorySessions(session);
        MemoryTurns turns = new MemoryTurns();
        RecordingJournal journal = new RecordingJournal();
        SessionOperationLockManager locks = new SessionOperationLockManager();
        CodingTurnApplicationService service = new CodingTurnApplicationService(
                sessions, turns, orchestrator, mock(GitChangeCollector.class), journal,
                JsonMapper.builder().findAndAddModules().build(), executor,
                () -> RuntimeReadiness.from(Instant.now(), List.of()),
                mock(SessionWorkspaceGuard.class), locks
        );
        return new Fixture(session, sessions, turns, journal,
                executor instanceof CapturingExecutor capturing ? capturing : null, service, locks);
    }

    /** 创建直接绑定真实仓库的测试 Session。 */
    private CodingSession activeSession() {
        Path repository = Path.of("target/reliability-workspace").toAbsolutePath();
        Instant now = Instant.parse("2026-09-08T00:00:00Z");
        return CodingSession.initializing(
                UUID.randomUUID(), "可靠性测试", repository, "main",
                new ChangePolicy(List.of("src/**"), List.of(".git/**")), now
        ).activateRealRepository(new GitRepositorySnapshot(
                repository, "main", "abc123", false
        ), now.plusSeconds(1));
    }

    private record Fixture(
            CodingSession session,
            MemorySessions sessions,
            MemoryTurns turns,
            RecordingJournal journal,
            CapturingExecutor executor,
            CodingTurnApplicationService service,
            SessionOperationLockManager locks
    ) { }

    private static final class CapturingExecutor implements TaskExecutor {
        private final List<Runnable> tasks = new ArrayList<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
    }

    private static final class MemorySessions implements CodingSessionRepository {
        private CodingSession session;
        private boolean failNativeSessionWrites;
        private boolean failProtectionWrites;
        private boolean replaceThenFailProtection;
        private boolean failProtectionRead;
        private boolean unreadableProtection;
        private boolean replaceThenFailNativeSession;
        private MemorySessions(CodingSession session) { this.session = session; }
        @Override public void save(CodingSession value) {
            if (failProtectionWrites && "PROCESS_CLEANUP_FAILED".equals(value.failureCode())) {
                if (replaceThenFailProtection) session = value;
                unreadableProtection = failProtectionRead;
                throw new IllegalStateException("protection write failed");
            }
            if (failNativeSessionWrites && value.claudeSessionId() != null) {
                throw new IllegalStateException("session write failed");
            }
            if (replaceThenFailNativeSession && value.claudeSessionId() != null) {
                session = value;
                throw new IllegalStateException("session write failed after replace");
            }
            session = value;
        }
        @Override public Optional<CodingSession> find(UUID id) {
            if (unreadableProtection) {
                unreadableProtection = false;
                throw new IllegalStateException("protection read failed");
            }
            return session.sessionId().equals(id) ? Optional.of(session) : Optional.empty();
        }
        @Override public List<CodingSession> findAll() { return List.of(session); }
    }

    private static final class MemoryTurns implements CodingTurnRepository {
        private final LinkedHashMap<UUID, CodingTurn> values = new LinkedHashMap<>();
        private final CountDownLatch terminalSaved = new CountDownLatch(1);
        private boolean failTerminalWrites;
        private boolean failRunningWrites;
        private boolean replaceThenFailRunning;
        private boolean replaceThenFailTerminal;
        @Override public void save(CodingTurn turn) {
            if (failRunningWrites && turn.status() == TurnStatus.RUNNING) {
                throw new IllegalStateException("running write failed");
            }
            if (replaceThenFailRunning && turn.status() == TurnStatus.RUNNING) {
                values.put(turn.turnId(), turn);
                throw new IllegalStateException("running write failed after replace");
            }
            if (failTerminalWrites && turn.status() != TurnStatus.QUEUED
                    && turn.status() != TurnStatus.RUNNING) {
                throw new IllegalStateException("terminal write failed");
            }
            if (replaceThenFailTerminal && turn.status() != TurnStatus.QUEUED
                    && turn.status() != TurnStatus.RUNNING) {
                values.put(turn.turnId(), turn);
                terminalSaved.countDown();
                throw new IllegalStateException("terminal write failed after replace");
            }
            values.put(turn.turnId(), turn);
            if (turn.status() != TurnStatus.QUEUED && turn.status() != TurnStatus.RUNNING) {
                terminalSaved.countDown();
            }
        }
        @Override public Optional<CodingTurn> find(UUID sessionId, UUID turnId) {
            return Optional.ofNullable(values.get(turnId));
        }
        @Override public List<CodingTurn> findAll(UUID sessionId) {
            return values.values().stream().filter(turn -> turn.sessionId().equals(sessionId)).toList();
        }
    }

    private static final class RecordingJournal implements RunEventJournal {
        private final List<RunEventType> types = new ArrayList<>();
        private boolean failTerminal;
        @Override public RunEvent append(String runId, String stage, int attempt,
                                         RunEventType type, JsonNode payload) {
            if (failTerminal && List.of(RunEventType.RUN_COMPLETED,
                    RunEventType.RUN_FAILED, RunEventType.RUN_CANCELLED).contains(type)) {
                throw new IllegalStateException("terminal event failed");
            }
            types.add(type);
            return new RunEvent(1, UUID.randomUUID(), runId, stage, attempt,
                    types.size(), Instant.now(), type, payload);
        }
        @Override public List<RunEvent> readAfter(String runId, long afterSequence) { return List.of(); }
    }
}
