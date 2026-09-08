package com.codingreview.controlplane.workflow.coding;

import com.codingreview.controlplane.adapter.claude.ClaudeCodeAdapter;
import com.codingreview.controlplane.adapter.claude.runtime.AgentEventSink;
import com.codingreview.controlplane.adapter.claude.runtime.ClaudeRunResult;
import com.codingreview.controlplane.adapter.claude.runtime.ClaudeRunStatus;
import com.codingreview.controlplane.event.AgentEvent;
import com.codingreview.controlplane.event.AgentEventType;
import com.codingreview.controlplane.observability.run.RunEventJournal;
import com.codingreview.controlplane.session.model.CodingSession;
import com.codingreview.controlplane.session.model.CodingTurn;
import com.codingreview.controlplane.session.model.TurnOutcome;
import com.codingreview.controlplane.session.guard.SessionWorkspaceDriftException;
import com.codingreview.controlplane.session.guard.SessionWorkspaceGuard;
import com.codingreview.controlplane.verification.maven.MavenVerifier;
import com.codingreview.controlplane.verification.maven.model.MavenVerificationResult;
import com.codingreview.controlplane.verification.maven.model.MavenVerificationStatus;
import com.codingreview.controlplane.workspace.change.GitChangeCollector;
import com.codingreview.controlplane.workspace.change.model.GitChangeSet;
import com.codingreview.controlplane.workspace.change.model.GitChangeType;
import com.codingreview.controlplane.workspace.change.model.GitChangedFile;
import com.codingreview.controlplane.workspace.change.policy.ChangePolicyEvaluator;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicy;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicyResult;
import com.codingreview.controlplane.workspace.git.model.GitRepositorySnapshot;
import com.codingreview.controlplane.workspace.model.WorkspaceHandle;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证 Coding 编排中的原生会话持久化时机和无修改边界。 */
class CodingTurnOrchestratorImplTest {

    @TempDir
    Path tempDir;

    @Test
    void stopsBeforeClaudeWhenWorkspaceDrifted() {
        ClaudeCodeAdapter claude = mock(ClaudeCodeAdapter.class);
        GitChangeCollector changes = mock(GitChangeCollector.class);
        ChangePolicyEvaluator policy = mock(ChangePolicyEvaluator.class);
        MavenVerifier maven = mock(MavenVerifier.class);
        RunEventJournal journal = mock(RunEventJournal.class);
        SessionWorkspaceGuard guard = mock(SessionWorkspaceGuard.class);
        CodingSession session = activeSession().advanceReviewBaseline(
                "def456", Instant.parse("2026-08-18T07:59:02Z")
        );
        CodingTurn running = CodingTurn.queued(
                UUID.randomUUID(), session.sessionId(), 1,
                "继续开发", Instant.parse("2026-08-18T08:00:00Z")
        ).running(Instant.parse("2026-08-18T08:00:01Z"));
        doThrow(new SessionWorkspaceDriftException(
                "SOURCE_HEAD_CHANGED", "真实仓库 HEAD 已变化。"
        )).when(guard).assertStable(session);
        CodingTurnOrchestratorImpl orchestrator = new CodingTurnOrchestratorImpl(
                claude, changes, policy, maven, journal,
                JsonMapper.builder().findAndAddModules().build(), guard
        );

        CodingTurnExecution execution = orchestrator.execute(session, running, ignored -> { });

        assertThat(execution.turn().outcome()).isEqualTo(TurnOutcome.WORKSPACE_DRIFT);
        assertThat(execution.turn().failureCode()).isEqualTo("SOURCE_HEAD_CHANGED");
        verify(claude, never()).run(any(), any());
        verify(changes, never()).collect(any());
    }

    @Test
    void persistsNativeClaudeSessionDuringInitEventAndSkipsMavenWithoutChanges() {
        ClaudeCodeAdapter claude = mock(ClaudeCodeAdapter.class);
        GitChangeCollector changes = mock(GitChangeCollector.class);
        ChangePolicyEvaluator policy = mock(ChangePolicyEvaluator.class);
        MavenVerifier maven = mock(MavenVerifier.class);
        RunEventJournal journal = mock(RunEventJournal.class);
        SessionWorkspaceGuard guard = mock(SessionWorkspaceGuard.class);
        CodingSession session = activeSession().advanceReviewBaseline(
                "def456", Instant.parse("2026-08-18T07:59:02Z")
        );
        CodingTurn running = CodingTurn.queued(
                UUID.randomUUID(), session.sessionId(), 1,
                "分析当前代码", Instant.parse("2026-08-18T08:00:00Z")
        ).running(Instant.parse("2026-08-18T08:00:01Z"));
        AtomicReference<CodingSession> persisted = new AtomicReference<>();

        when(claude.run(any(), any())).thenAnswer(invocation -> {
            AgentEventSink sink = invocation.getArgument(1);
            sink.onEvent(new AgentEvent(
                    AgentEventType.RUNTIME_INITIALIZED,
                    "native-claude-session",
                    JsonNodeFactory.instance.objectNode()
            ));
            assertThat(persisted.get().claudeSessionId())
                    .isEqualTo("native-claude-session");
            Instant started = Instant.parse("2026-08-18T08:00:01Z");
            return new ClaudeRunResult(
                    ClaudeRunStatus.COMPLETED, 0, true, "native-claude-session",
                    true,
                    new AgentEvent(AgentEventType.RUNTIME_COMPLETED,
                            "native-claude-session", JsonNodeFactory.instance.objectNode()),
                    started, started.plusSeconds(1), null
            );
        });
        when(changes.collect(any())).thenReturn(new GitChangeSet(
                session.activeReviewBaselineCommit(), session.sourceRepository(),
                List.of(), "", 0
        ));
        CodingTurnOrchestratorImpl orchestrator = new CodingTurnOrchestratorImpl(
                claude, changes, policy, maven, journal,
                JsonMapper.builder().findAndAddModules().build(), guard
        );

        CodingTurnExecution execution = orchestrator.execute(
                session, running, persisted::set
        );

        assertThat(execution.session().claudeSessionId())
                .isEqualTo("native-claude-session");
        assertThat(execution.turn().outcome()).isEqualTo(TurnOutcome.NO_CHANGES);
        verify(changes).collect(new WorkspaceHandle(
                session.sourceRepository(), session.activeReviewBaselineCommit()
        ));
        verify(policy, never()).evaluate(any(), any());
        verify(maven, never()).verify(any());
    }

    @Test
    void invalidatesVerificationWhenWorkspaceChangesAfterMaven() {
        ClaudeCodeAdapter claude = mock(ClaudeCodeAdapter.class);
        GitChangeCollector changes = mock(GitChangeCollector.class);
        ChangePolicyEvaluator policy = mock(ChangePolicyEvaluator.class);
        MavenVerifier maven = mock(MavenVerifier.class);
        RunEventJournal journal = mock(RunEventJournal.class);
        SessionWorkspaceGuard guard = mock(SessionWorkspaceGuard.class);
        CodingSession session = activeSession().advanceReviewBaseline(
                "def456", Instant.parse("2026-08-18T07:59:02Z")
        );
        CodingTurn running = runningTurn(session);
        GitChangeSet before = changedSet("before");
        GitChangeSet after = changedSet("after");
        when(claude.run(any(), any())).thenReturn(completedClaude());
        when(changes.collect(any())).thenReturn(before, after);
        when(policy.evaluate(any(), any())).thenReturn(new ChangePolicyResult(List.of()));
        when(maven.verify(any())).thenReturn(passedMaven());

        CodingTurnExecution execution = new CodingTurnOrchestratorImpl(
                claude, changes, policy, maven, journal,
                JsonMapper.builder().findAndAddModules().build(), guard
        ).execute(session, running, ignored -> { });

        assertThat(execution.turn().outcome()).isEqualTo(TurnOutcome.WORKSPACE_DRIFT);
        assertThat(execution.turn().failureCode()).isEqualTo("WORKSPACE_CHANGED_DURING_VERIFICATION");
        verify(changes, org.mockito.Mockito.times(2)).collect(any());
    }

    @Test
    void keepsVerifiedOutcomeWhenWorkspaceFingerprintIsUnchanged() {
        ClaudeCodeAdapter claude = mock(ClaudeCodeAdapter.class);
        GitChangeCollector changes = mock(GitChangeCollector.class);
        ChangePolicyEvaluator policy = mock(ChangePolicyEvaluator.class);
        MavenVerifier maven = mock(MavenVerifier.class);
        RunEventJournal journal = mock(RunEventJournal.class);
        SessionWorkspaceGuard guard = mock(SessionWorkspaceGuard.class);
        CodingSession session = activeSession().advanceReviewBaseline(
                "def456", Instant.parse("2026-08-18T07:59:02Z")
        );
        CodingTurn running = runningTurn(session);
        GitChangeSet changeSet = changedSet("same");
        when(claude.run(any(), any())).thenReturn(completedClaude());
        when(changes.collect(any())).thenReturn(changeSet, changeSet);
        when(policy.evaluate(any(), any())).thenReturn(new ChangePolicyResult(List.of()));
        when(maven.verify(any())).thenReturn(passedMaven());

        CodingTurnExecution execution = new CodingTurnOrchestratorImpl(
                claude, changes, policy, maven, journal,
                JsonMapper.builder().findAndAddModules().build(), guard
        ).execute(session, running, ignored -> { });

        assertThat(execution.turn().outcome()).isEqualTo(TurnOutcome.CHANGES_VERIFIED);
        verify(changes, org.mockito.Mockito.times(2)).collect(any());
    }

    /** 创建带有代码变更的合成快照。 */
    private GitChangeSet changedSet(String diff) {
        Path workspace = tempDir.resolve("snapshot-" + diff);
        try {
            Files.createDirectories(workspace.resolve("src"));
            Files.writeString(workspace.resolve("src/App.java"), diff, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建测试工作区。", exception);
        }
        return new GitChangeSet(
                "def456", workspace,
                List.of(new GitChangedFile("src/App.java", null, GitChangeType.MODIFIED, false)),
                diff, diff.length()
        );
    }

    /** 创建可直接返回的已完成 Claude 结果。 */
    private ClaudeRunResult completedClaude() {
        Instant started = Instant.parse("2026-08-18T08:00:01Z");
        return new ClaudeRunResult(
                ClaudeRunStatus.COMPLETED, 0, true, "native-claude-session", true,
                new AgentEvent(AgentEventType.RUNTIME_COMPLETED,
                        "native-claude-session", JsonNodeFactory.instance.objectNode()),
                started, started.plusSeconds(1), null
        );
    }

    /** 创建通过的 Maven 结果。 */
    private MavenVerificationResult passedMaven() {
        Instant started = Instant.parse("2026-08-18T08:00:02Z");
        return new MavenVerificationResult(
                MavenVerificationStatus.PASSED, 0, List.of("mvn", "clean", "test"),
                started, started.plusSeconds(1), null, null, null, "Maven 验证通过", null
        );
    }

    /** 创建运行中的测试 Turn。 */
    private CodingTurn runningTurn(CodingSession session) {
        return CodingTurn.queued(
                UUID.randomUUID(), session.sessionId(), 1,
                "修改代码", Instant.parse("2026-08-18T08:00:00Z")
        ).running(Instant.parse("2026-08-18T08:00:01Z"));
    }

    /** 创建直接绑定真实 Git 工作区的测试 Session。 */
    private CodingSession activeSession() {
        UUID sessionId = UUID.randomUUID();
        Path repository = Path.of("D:/test/repository").toAbsolutePath();
        Instant now = Instant.parse("2026-08-18T07:59:00Z");
        return CodingSession.initializing(
                sessionId, "测试会话", repository, "HEAD",
                new ChangePolicy(List.of("src/**"), List.of(".git/**")), now
        ).activateRealRepository(
                new GitRepositorySnapshot(repository, "main", "abc123", false),
                now.plusSeconds(1)
        );
    }
}
