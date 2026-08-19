package com.codingreview.controlplane.application.session;

import com.codingreview.controlplane.observability.run.RunEventJournal;
import com.codingreview.controlplane.application.session.error.CodingSessionConflictException;
import com.codingreview.controlplane.runtime.readiness.RuntimeReadinessService;
import com.codingreview.controlplane.runtime.readiness.error.RuntimeNotReadyException;
import com.codingreview.controlplane.runtime.readiness.model.RuntimeCheck;
import com.codingreview.controlplane.runtime.readiness.model.RuntimeCheckStatus;
import com.codingreview.controlplane.runtime.readiness.model.RuntimeComponent;
import com.codingreview.controlplane.runtime.readiness.model.RuntimeReadiness;
import com.codingreview.controlplane.session.model.CodingSession;
import com.codingreview.controlplane.session.model.CodingSessionStatus;
import com.codingreview.controlplane.session.model.CodingTurn;
import com.codingreview.controlplane.session.model.TurnStatus;
import com.codingreview.controlplane.session.persistence.CodingSessionRepository;
import com.codingreview.controlplane.session.persistence.CodingTurnRepository;
import com.codingreview.controlplane.verification.maven.MavenVerifier;
import com.codingreview.controlplane.workflow.m6.CodingTurnOrchestrator;
import com.codingreview.controlplane.workspace.change.GitChangeCollector;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicy;
import com.codingreview.controlplane.workspace.worktree.model.WorktreeHandle;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证 Turn 创建的环境门禁、Session 串行约束和排队取消语义。 */
class CodingTurnApplicationServiceTest {

    @Test
    void refusesToCreateTurnWhenRuntimeIsNotReady() {
        Fixture fixture = fixture(RuntimeReadiness.from(Instant.now(), List.of(
                new RuntimeCheck(RuntimeComponent.CLAUDE_CODE, RuntimeCheckStatus.FAILED,
                        true, null, "claude.cmd", "Claude 不可用", "请修复 Claude Code")
        )));
        when(fixture.turns.findAll(fixture.session.sessionId())).thenReturn(List.of());

        assertThatThrownBy(() -> fixture.service.submit(fixture.session.sessionId(), "继续开发"))
                .isInstanceOf(RuntimeNotReadyException.class);
        verify(fixture.turns, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void refusesConcurrentTurnInSameSession() {
        Fixture fixture = fixture(RuntimeReadiness.from(Instant.now(), List.of()));
        when(fixture.turns.findAll(fixture.session.sessionId())).thenReturn(List.of());

        CodingTurn first = fixture.service.submit(fixture.session.sessionId(), "第一轮");

        assertThatThrownBy(() -> fixture.service.submit(fixture.session.sessionId(), "第二轮"))
                .isInstanceOf(CodingSessionConflictException.class)
                .hasMessageContaining("已有正在执行");
        assertThat(first.status()).isEqualTo(TurnStatus.QUEUED);
        assertThat(fixture.executor.tasks).hasSize(1);
    }

    @Test
    void cancelsQueuedTurnAndPreventsWorkerStart() {
        Fixture fixture = fixture(RuntimeReadiness.from(Instant.now(), List.of()));
        when(fixture.turns.findAll(fixture.session.sessionId())).thenReturn(List.of());
        CodingTurn queued = fixture.service.submit(fixture.session.sessionId(), "等待执行");
        when(fixture.turns.find(fixture.session.sessionId(), queued.turnId()))
                .thenReturn(Optional.of(queued));

        CodingTurn cancelled = fixture.service.cancel(fixture.session.sessionId(), queued.turnId());

        assertThat(cancelled.status()).isEqualTo(TurnStatus.CANCELLED);
        assertThat(fixture.executor.tasks.getFirst()).isNotNull();
        verify(fixture.turns).save(org.mockito.ArgumentMatchers.argThat(
                turn -> turn.turnId().equals(queued.turnId()) && turn.status() == TurnStatus.CANCELLED
        ));
    }

    /** 创建使用内存替身的 Turn 应用服务。 */
    private Fixture fixture(RuntimeReadiness readiness) {
        CodingSessionRepository sessions = mock(CodingSessionRepository.class);
        CodingTurnRepository turns = mock(CodingTurnRepository.class);
        CodingTurnOrchestrator orchestrator = mock(CodingTurnOrchestrator.class);
        GitChangeCollector changes = mock(GitChangeCollector.class);
        RunEventJournal journal = mock(RunEventJournal.class);
        RuntimeReadinessService readinessService = () -> readiness;
        CapturingExecutor executor = new CapturingExecutor();
        CodingSession session = activeSession();
        when(sessions.find(session.sessionId())).thenReturn(Optional.of(session));
        CodingTurnApplicationService service = new CodingTurnApplicationService(
                sessions, turns, orchestrator, changes, journal,
                JsonMapper.builder().findAndAddModules().build(), executor, readinessService
        );
        return new Fixture(session, turns, executor, service);
    }

    /** 创建具备长期 Worktree 的测试 Session。 */
    private CodingSession activeSession() {
        UUID sessionId = UUID.randomUUID();
        Path repository = Path.of("D:/test/repository").toAbsolutePath();
        Path worktree = Path.of("D:/test/worktrees/" + sessionId).toAbsolutePath();
        Instant now = Instant.parse("2026-08-18T07:59:00Z");
        return CodingSession.initializing(
                sessionId, "测试会话", repository, "HEAD",
                new ChangePolicy(List.of("src/**"), List.of(".git/**")), now
        ).activate(new WorktreeHandle(
                repository, worktree, sessionId.toString(), "HEAD", "abc123", false
        ), now.plusSeconds(1));
    }

    /** 只记录任务、不立即运行的执行器，用于检查排队和取消竞态。 */
    private static final class CapturingExecutor implements TaskExecutor {
        private final List<Runnable> tasks = new ArrayList<>();

        /** 保存提交的任务，交由测试决定何时启动。 */
        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }
    }

    /** 聚合测试替身和被测对象。 */
    private record Fixture(
            CodingSession session,
            CodingTurnRepository turns,
            CapturingExecutor executor,
            CodingTurnApplicationService service
    ) { }
}
