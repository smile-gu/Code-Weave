package com.codingreview.controlplane.workflow.m0;

import com.codingreview.controlplane.adapter.claude.ClaudeCodeAdapter;
import com.codingreview.controlplane.adapter.claude.runtime.AgentEventSink;
import com.codingreview.controlplane.adapter.claude.runtime.ClaudeRunResult;
import com.codingreview.controlplane.adapter.claude.runtime.ClaudeRunStatus;
import com.codingreview.controlplane.event.AgentEvent;
import com.codingreview.controlplane.event.AgentEventType;
import com.codingreview.controlplane.observability.run.RunEvent;
import com.codingreview.controlplane.observability.run.RunEventJournal;
import com.codingreview.controlplane.observability.run.RunEventType;
import com.codingreview.controlplane.observability.run.error.RunEventWriteException;
import com.codingreview.controlplane.verification.maven.MavenVerifier;
import com.codingreview.controlplane.verification.maven.model.MavenVerificationResult;
import com.codingreview.controlplane.verification.maven.model.MavenVerificationStatus;
import com.codingreview.controlplane.workflow.m0.model.M0RunFailureCode;
import com.codingreview.controlplane.workflow.m0.model.M0RunRequest;
import com.codingreview.controlplane.workflow.m0.model.M0RunResult;
import com.codingreview.controlplane.workflow.m0.model.M0RunStage;
import com.codingreview.controlplane.workflow.m0.model.M0RunStatus;
import com.codingreview.controlplane.workspace.change.GitChangeCollector;
import com.codingreview.controlplane.workspace.change.model.GitChangeSet;
import com.codingreview.controlplane.workspace.change.model.GitChangeType;
import com.codingreview.controlplane.workspace.change.model.GitChangedFile;
import com.codingreview.controlplane.workspace.change.policy.ChangePolicyEvaluator;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicy;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicyResult;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicyViolation;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicyViolationType;
import com.codingreview.controlplane.workspace.worktree.GitWorktreeManager;
import com.codingreview.controlplane.workspace.worktree.error.WorktreeException;
import com.codingreview.controlplane.workspace.worktree.error.WorktreeFailureType;
import com.codingreview.controlplane.workspace.worktree.model.WorktreeCreateResult;
import com.codingreview.controlplane.workspace.worktree.model.WorktreeHandle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证 M0 显式编排的固定顺序、失败短路和事件闭环。 */
class M0RunOrchestratorImplTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final GitWorktreeManager worktreeManager = mock(GitWorktreeManager.class);
    private final ClaudeCodeAdapter claudeCodeAdapter = mock(ClaudeCodeAdapter.class);
    private final GitChangeCollector changeCollector = mock(GitChangeCollector.class);
    private final ChangePolicyEvaluator changePolicyEvaluator = mock(ChangePolicyEvaluator.class);
    private final MavenVerifier mavenVerifier = mock(MavenVerifier.class);
    private final InMemoryRunEventJournal eventJournal = new InMemoryRunEventJournal();

    private M0RunRequest request;
    private WorktreeCreateResult worktree;
    private GitChangeSet changes;
    private ChangePolicyResult compliantPolicy;
    private MavenVerificationResult passedMaven;

    @BeforeEach
    void setUp() {
        UUID runId = UUID.randomUUID();
        ChangePolicy policy = new ChangePolicy(List.of("src/**"), List.of(".git/**"));
        request = new M0RunRequest(
                runId,
                Path.of("D:/projects/demo"),
                "HEAD",
                "实现健康检查接口",
                List.of("Maven 测试通过"),
                policy
        );
        WorktreeHandle handle = new WorktreeHandle(
                request.repository(),
                Path.of("D:/runtime/worktrees/" + runId),
                runId.toString(),
                "HEAD",
                "0123456789abcdef",
                false
        );
        worktree = new WorktreeCreateResult(handle, List.of());
        changes = new GitChangeSet(
                handle.resolvedCommit(),
                handle.worktreePath(),
                List.of(new GitChangedFile(
                        "src/main/java/Demo.java", null, GitChangeType.MODIFIED, false
                )),
                "diff --git a/src/main/java/Demo.java b/src/main/java/Demo.java",
                64
        );
        compliantPolicy = new ChangePolicyResult(List.of());
        passedMaven = mavenResult(MavenVerificationStatus.PASSED, 0, "验证通过");
    }

    @Test
    void executesFixedPipelineAndPublishesClosedEventSequence() {
        stubSuccessfulDependencies();
        when(claudeCodeAdapter.run(any(), any())).thenAnswer(invocation -> {
            AgentEventSink sink = invocation.getArgument(1);
            sink.onEvent(new AgentEvent(
                    AgentEventType.MESSAGE_COMPLETED,
                    "message-1",
                    objectMapper.createObjectNode().put("text", "done")
            ));
            return completedClaude();
        });

        M0RunResult result = orchestrator(eventJournal).execute(request);

        assertThat(result.status()).isEqualTo(M0RunStatus.COMPLETED);
        assertThat(result.worktree()).isSameAs(worktree);
        assertThat(result.changes()).isSameAs(changes);
        assertThat(result.changePolicy()).isSameAs(compliantPolicy);
        assertThat(result.maven()).isSameAs(passedMaven);

        InOrder order = inOrder(
                worktreeManager, claudeCodeAdapter, changeCollector,
                changePolicyEvaluator, mavenVerifier
        );
        order.verify(worktreeManager).create(any());
        order.verify(claudeCodeAdapter).run(any(), any());
        order.verify(changeCollector).collect(worktree.handle());
        order.verify(changePolicyEvaluator).evaluate(changes, request.changePolicy());
        order.verify(mavenVerifier).verify(any());
        verify(worktreeManager, never()).cleanup(any());
        verify(worktreeManager, never()).forceCleanup(any());

        assertThat(eventJournal.events())
                .extracting(RunEvent::type)
                .containsExactly(
                        RunEventType.STAGE_STARTED,
                        RunEventType.STAGE_COMPLETED,
                        RunEventType.STAGE_STARTED,
                        RunEventType.AGENT_EVENT_OBSERVED,
                        RunEventType.STAGE_COMPLETED,
                        RunEventType.STAGE_STARTED,
                        RunEventType.STAGE_COMPLETED,
                        RunEventType.STAGE_STARTED,
                        RunEventType.STAGE_COMPLETED,
                        RunEventType.STAGE_STARTED,
                        RunEventType.STAGE_COMPLETED,
                        RunEventType.RUN_COMPLETED
                );
        RunEvent agentEvent = eventJournal.events().stream()
                .filter(event -> event.type() == RunEventType.AGENT_EVENT_OBSERVED)
                .findFirst()
                .orElseThrow();
        assertThat(agentEvent.payload().path("agentEventType").asText())
                .isEqualTo("MESSAGE_COMPLETED");
    }

    @Test
    void stopsBeforePolicyAndMavenWhenDiffIsEmpty() {
        GitChangeSet emptyChanges = new GitChangeSet(
                worktree.handle().resolvedCommit(),
                worktree.handle().worktreePath(),
                List.of(),
                "",
                0
        );
        when(worktreeManager.create(any())).thenReturn(worktree);
        when(claudeCodeAdapter.run(any(), any())).thenReturn(completedClaude());
        when(changeCollector.collect(worktree.handle())).thenReturn(emptyChanges);

        M0RunResult result = orchestrator(eventJournal).execute(request);

        assertThat(result.status()).isEqualTo(M0RunStatus.FAILED);
        assertThat(result.failedStage()).isEqualTo(M0RunStage.CHANGE_POLICY);
        assertThat(result.failureCode()).isEqualTo(M0RunFailureCode.NO_CHANGES);
        verifyNoInteractions(changePolicyEvaluator, mavenVerifier);
        verify(worktreeManager, never()).cleanup(any());
        assertThat(eventJournal.events())
                .extracting(RunEvent::type)
                .endsWith(RunEventType.STAGE_FAILED, RunEventType.RUN_FAILED);
    }

    @Test
    void rejectsPathViolationWithoutRunningMaven() {
        ChangePolicyResult rejected = new ChangePolicyResult(List.of(
                new ChangePolicyViolation(
                        "pom.xml",
                        GitChangeType.MODIFIED,
                        ChangePolicyViolationType.OUTSIDE_ALLOWED_PATHS,
                        null,
                        "文件不在允许范围内"
                )
        ));
        when(worktreeManager.create(any())).thenReturn(worktree);
        when(claudeCodeAdapter.run(any(), any())).thenReturn(completedClaude());
        when(changeCollector.collect(worktree.handle())).thenReturn(changes);
        when(changePolicyEvaluator.evaluate(changes, request.changePolicy())).thenReturn(rejected);

        M0RunResult result = orchestrator(eventJournal).execute(request);

        assertThat(result.failureCode()).isEqualTo(M0RunFailureCode.CHANGE_POLICY_REJECTED);
        assertThat(result.failedStage()).isEqualTo(M0RunStage.CHANGE_POLICY);
        verifyNoInteractions(mavenVerifier);
        verify(worktreeManager, never()).cleanup(any());
    }

    @Test
    void stopsAfterClaudeFailureAndKeepsWorktree() {
        when(worktreeManager.create(any())).thenReturn(worktree);
        when(claudeCodeAdapter.run(any(), any())).thenReturn(claudeResult(
                ClaudeRunStatus.PROCESS_FAILED, 1, "Claude process failed"
        ));

        M0RunResult result = orchestrator(eventJournal).execute(request);

        assertThat(result.failureCode()).isEqualTo(M0RunFailureCode.CODING_FAILED);
        assertThat(result.failedStage()).isEqualTo(M0RunStage.CODING);
        verifyNoInteractions(changeCollector, changePolicyEvaluator, mavenVerifier);
        verify(worktreeManager, never()).cleanup(any());
        assertThat(eventJournal.events())
                .extracting(RunEvent::type)
                .endsWith(RunEventType.STAGE_FAILED, RunEventType.RUN_FAILED);
    }

    @Test
    void stopsAtWorktreeFailureBeforeInvokingLaterModules() {
        when(worktreeManager.create(any())).thenThrow(new WorktreeException(
                WorktreeFailureType.INVALID_BASE_REF,
                "找不到指定的基准版本，无法创建本次任务环境。",
                "unknown revision"
        ));

        M0RunResult result = orchestrator(eventJournal).execute(request);

        assertThat(result.status()).isEqualTo(M0RunStatus.FAILED);
        assertThat(result.failedStage()).isEqualTo(M0RunStage.WORKTREE);
        assertThat(result.failureCode()).isEqualTo(M0RunFailureCode.WORKTREE_FAILED);
        assertThat(result.worktree()).isNull();
        verifyNoInteractions(claudeCodeAdapter, changeCollector, changePolicyEvaluator, mavenVerifier);
        assertThat(eventJournal.events())
                .extracting(RunEvent::type)
                .containsExactly(
                        RunEventType.STAGE_STARTED,
                        RunEventType.STAGE_FAILED,
                        RunEventType.RUN_FAILED
                );
    }

    @Test
    void propagatesAdapterEventPublishFailureAsFatalJournalError() {
        when(worktreeManager.create(any())).thenReturn(worktree);
        when(claudeCodeAdapter.run(any(), any())).thenReturn(claudeResult(
                ClaudeRunStatus.EVENT_PUBLISH_FAILED,
                null,
                "Agent event sink failed: disk unavailable"
        ));

        assertThatThrownBy(() -> orchestrator(eventJournal).execute(request))
                .isInstanceOf(RunEventWriteException.class)
                .hasMessageContaining("disk unavailable");
        verifyNoInteractions(changeCollector, changePolicyEvaluator, mavenVerifier);
        verify(worktreeManager, never()).cleanup(any());
        assertThat(eventJournal.events())
                .extracting(RunEvent::type)
                .doesNotContain(RunEventType.STAGE_FAILED, RunEventType.RUN_FAILED);
    }

    @Test
    void mapsMavenCancellationToCancelledRun() {
        stubSuccessfulDependencies();
        MavenVerificationResult cancelled = mavenResult(
                MavenVerificationStatus.CANCELLED, null, "验证已取消"
        );
        when(mavenVerifier.verify(any())).thenReturn(cancelled);

        M0RunResult result = orchestrator(eventJournal).execute(request);

        assertThat(result.status()).isEqualTo(M0RunStatus.CANCELLED);
        assertThat(result.failureCode()).isEqualTo(M0RunFailureCode.CANCELLED);
        assertThat(result.failedStage()).isEqualTo(M0RunStage.MAVEN);
        verify(worktreeManager, never()).cleanup(any());
        assertThat(eventJournal.events())
                .extracting(RunEvent::type)
                .endsWith(RunEventType.STAGE_FAILED, RunEventType.RUN_CANCELLED);
    }

    @Test
    void propagatesJournalFailureAndStopsBusinessExecution() {
        RunEventJournal failingJournal = new RunEventJournal() {
            private int appends;

            @Override
            public RunEvent append(
                    String runId,
                    String stage,
                    int attempt,
                    RunEventType type,
                    JsonNode payload
            ) {
                appends++;
                if (appends == 1) {
                    throw new RunEventWriteException(
                            "事件日志写入失败", "simulated journal failure", null
                    );
                }
                return event(runId, stage, attempt, type, payload, appends);
            }

            @Override
            public List<RunEvent> readAfter(String runId, long afterSequence) {
                return List.of();
            }
        };

        assertThatThrownBy(() -> orchestrator(failingJournal).execute(request))
                .isInstanceOf(RunEventWriteException.class)
                .hasMessageContaining("simulated journal failure");
        verifyNoInteractions(worktreeManager, claudeCodeAdapter, changeCollector,
                changePolicyEvaluator, mavenVerifier);
    }

    private void stubSuccessfulDependencies() {
        when(worktreeManager.create(any())).thenReturn(worktree);
        when(claudeCodeAdapter.run(any(), any())).thenReturn(completedClaude());
        when(changeCollector.collect(worktree.handle())).thenReturn(changes);
        when(changePolicyEvaluator.evaluate(changes, request.changePolicy()))
                .thenReturn(compliantPolicy);
        when(mavenVerifier.verify(any())).thenReturn(passedMaven);
    }

    private M0RunOrchestrator orchestrator(RunEventJournal journal) {
        return new M0RunOrchestratorImpl(
                worktreeManager,
                claudeCodeAdapter,
                changeCollector,
                changePolicyEvaluator,
                mavenVerifier,
                journal,
                objectMapper
        );
    }

    private ClaudeRunResult completedClaude() {
        return claudeResult(ClaudeRunStatus.COMPLETED, 0, null);
    }

    private ClaudeRunResult claudeResult(
            ClaudeRunStatus status,
            Integer exitCode,
            String diagnostic
    ) {
        Instant startedAt = Instant.parse("2026-08-14T03:00:00Z");
        return new ClaudeRunResult(
                status,
                exitCode,
                true,
                false,
                null,
                startedAt,
                startedAt.plusSeconds(1),
                diagnostic
        );
    }

    private MavenVerificationResult mavenResult(
            MavenVerificationStatus status,
            Integer exitCode,
            String userMessage
    ) {
        Instant startedAt = Instant.parse("2026-08-14T03:00:00Z");
        return new MavenVerificationResult(
                status,
                exitCode,
                List.of("mvn", "test"),
                startedAt,
                startedAt.plusSeconds(2),
                null,
                null,
                null,
                userMessage,
                status == MavenVerificationStatus.PASSED ? null : "verification stopped"
        );
    }

    private static RunEvent event(
            String runId,
            String stage,
            int attempt,
            RunEventType type,
            JsonNode payload,
            long sequence
    ) {
        return new RunEvent(
                RunEvent.CURRENT_SCHEMA_VERSION,
                UUID.randomUUID(),
                runId,
                stage,
                attempt,
                sequence,
                Instant.parse("2026-08-14T03:00:00Z").plusSeconds(sequence),
                type,
                payload
        );
    }

    private static final class InMemoryRunEventJournal implements RunEventJournal {
        private final List<RunEvent> events = new ArrayList<>();

        @Override
        public RunEvent append(
                String runId,
                String stage,
                int attempt,
                RunEventType type,
                JsonNode payload
        ) {
            RunEvent runEvent = event(
                    runId, stage, attempt, type, payload, events.size() + 1L
            );
            events.add(runEvent);
            return runEvent;
        }

        @Override
        public List<RunEvent> readAfter(String runId, long afterSequence) {
            return events.stream()
                    .filter(event -> event.runId().equals(runId))
                    .filter(event -> event.sequence() > afterSequence)
                    .toList();
        }

        List<RunEvent> events() {
            return List.copyOf(events);
        }
    }
}
