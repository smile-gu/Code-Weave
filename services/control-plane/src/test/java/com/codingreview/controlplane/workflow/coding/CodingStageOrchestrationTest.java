package com.codingreview.controlplane.workflow.coding;

import com.codingreview.controlplane.adapter.claude.ClaudeCodeAdapter;
import com.codingreview.controlplane.adapter.claude.runtime.*;
import com.codingreview.controlplane.event.*;
import com.codingreview.controlplane.observability.run.*;
import com.codingreview.controlplane.session.guard.*;
import com.codingreview.controlplane.session.model.*;
import com.codingreview.controlplane.verification.maven.*;
import com.codingreview.controlplane.verification.maven.model.*;
import com.codingreview.controlplane.workspace.change.*;
import com.codingreview.controlplane.workspace.change.model.*;
import com.codingreview.controlplane.workspace.change.policy.*;
import com.codingreview.controlplane.workspace.change.policy.model.*;
import com.codingreview.controlplane.workspace.git.model.GitRepositorySnapshot;
import com.codingreview.controlplane.workflow.coding.stage.CodingStage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 覆盖五阶段事实、业务失败、取消及不确定日志写入的停止边界。 */
class CodingStageOrchestrationTest {
    @TempDir Path root;
    final ClaudeCodeAdapter claude = mock(ClaudeCodeAdapter.class);
    final GitChangeCollector changes = mock(GitChangeCollector.class);
    final ChangePolicyEvaluator policy = mock(ChangePolicyEvaluator.class);
    final MavenVerifier maven = mock(MavenVerifier.class);
    final SessionWorkspaceGuard guard = mock(SessionWorkspaceGuard.class);
    final RunEventJournal journal = mock(RunEventJournal.class);
    final List<Fact> facts = new ArrayList<>();
    final AtomicBoolean cancellation = new AtomicBoolean();
    final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
    CodingSession session;
    CodingTurn turn;
    CodingTurnOrchestratorImpl orchestrator;
    String failStage;
    RunEventType failType;

    /** 为每个场景创建真实可读文件与可控依赖。 */
    @BeforeEach void setup() throws Exception {
        Files.writeString(root.resolve("App.java"), "before");
        session = CodingSession.initializing(UUID.randomUUID(), "阶段测试", root, "HEAD",
                new ChangePolicy(List.of("**"), List.of(".git/**")), Instant.now())
                .activateRealRepository(new GitRepositorySnapshot(root, "main", "abc", false), Instant.now());
        turn = CodingTurn.queued(UUID.randomUUID(), session.sessionId(), 1, "开发", Instant.now()).running(Instant.now());
        when(claude.run(any(), any())).thenReturn(claudeResult(ClaudeRunStatus.COMPLETED));
        when(changes.collect(any())).thenAnswer(i -> new GitChangeSet("abc", root,
                List.of(new GitChangedFile("App.java", null, GitChangeType.MODIFIED, false)), "diff", 4));
        when(policy.evaluate(any(), any())).thenReturn(new ChangePolicyResult(List.of()));
        when(maven.verify(any())).thenReturn(mavenResult(MavenVerificationStatus.PASSED));
        when(journal.append(anyString(), anyString(), anyInt(), any(), any())).thenAnswer(i -> {
            String stage = i.getArgument(1);
            RunEventType type = i.getArgument(3);
            if (type == failType && Objects.equals(stage, failStage)) throw new IllegalStateException("injected write failure");
            facts.add(new Fact(stage, type, i.getArgument(4)));
            return null;
        });
        orchestrator = new CodingTurnOrchestratorImpl(claude, changes, policy, maven, journal, mapper, guard);
    }

    /** 正常路径只结束一次，并且编排器不发布任务终态。 */
    @Test void completesFiveStagesInOrder() {
        var result = execute();
        assertThat(result.outcome()).isEqualTo(TurnOutcome.CHANGES_VERIFIED);
        assertThat(facts.stream().filter(f -> f.type == RunEventType.STAGE_COMPLETED).map(Fact::stage))
                .containsExactly("workspace", "coding", "change-collection", "change-policy", "maven");
        assertThat(facts).noneMatch(f -> f.type == RunEventType.RUN_COMPLETED || f.type == RunEventType.RUN_FAILED);
        assertThat(facts.stream().filter(f -> f.type.name().startsWith("STAGE_")))
                .allMatch(f -> f.payload.path("stageProtocolVersion").asInt() == 1);
    }

    /** 开始写入失败后该阶段业务不启动。 */
    @ParameterizedTest @EnumSource(CodingStage.class)
    void doesNotExecuteStageWhoseStartCannotBeSaved(CodingStage stage) {
        failStage = stage.id(); failType = RunEventType.STAGE_STARTED;
        var result = execute();
        assertThat(result.failureCode()).isEqualTo("EXECUTION_EVENT_PERSISTENCE_FAILED");
        assertThat(result.failedStage()).isEqualTo(stage.id());
        assertThat(facts).noneMatch(f -> f.stage.equals(stage.id()));
        switch (stage) {
            case WORKSPACE -> verifyNoInteractions(guard, claude, changes, policy, maven);
            case CODING -> verifyNoInteractions(claude, changes, policy, maven);
            case CHANGE_COLLECTION -> verifyNoInteractions(changes, policy, maven);
            case CHANGE_POLICY -> verifyNoInteractions(policy, maven);
            case MAVEN -> verifyNoInteractions(maven);
        }
    }

    /** 完成事实写入失败后不追加后续阶段或伪造失败事实。 */
    @ParameterizedTest @EnumSource(CodingStage.class)
    void stopsAfterEndWriteFailure(CodingStage stage) {
        failStage = stage.id(); failType = RunEventType.STAGE_COMPLETED;
        var result = execute();
        assertThat(result.failureCode()).isEqualTo("EXECUTION_EVENT_PERSISTENCE_FAILED");
        assertThat(result.failedStage()).isEqualTo(stage.id());
        assertThat(facts.getLast().type).isEqualTo(RunEventType.STAGE_STARTED);
        assertThat(facts.getLast().stage).isEqualTo(stage.id());
        if (stage != CodingStage.MAVEN) verifyNoInteractions(maven);
    }

    /** 初始工作区失败不启动 Agent，后续阶段有明确跳过事实。 */
    @Test void workspaceFailureSkipsDownstream() {
        doThrow(new SessionWorkspaceDriftException("SOURCE_HEAD_CHANGED", "HEAD 变化")).when(guard).assertStable(any());
        var result = execute();
        assertThat(result.failedStage()).isEqualTo("workspace");
        assertThat(result.failureKind()).isEqualTo("WORKSPACE_DRIFT");
        assertThat(skipped()).hasSize(4);
        verifyNoInteractions(claude);
    }

    /** 空变更完成前三阶段，后两阶段跳过。 */
    @Test void noChangesSkipsPolicyAndMaven() {
        when(changes.collect(any())).thenReturn(new GitChangeSet("abc", root, List.of(), "", 0));
        assertThat(execute().outcome()).isEqualTo(TurnOutcome.NO_CHANGES);
        assertThat(skipped()).extracting(Fact::stage).containsExactly("change-policy", "maven");
        assertThat(skipped()).allMatch(f -> f.payload.path("reasonCode").asText().equals("NO_CHANGES"));
        verifyNoInteractions(policy, maven);
    }

    /** Agent 各类退出均在 Runner 返回后生成阶段结论。 */
    @ParameterizedTest @EnumSource(value = ClaudeRunStatus.class, names = {"AGENT_FAILED", "START_FAILED", "CANCELLED", "PROCESS_CLEANUP_FAILED"})
    void mapsClaudeFailure(ClaudeRunStatus status) {
        when(claude.run(any(), any())).thenReturn(claudeResult(status));
        var result = execute();
        assertThat(result.failedStage()).isEqualTo("coding");
        assertThat(result.failureKind()).isEqualTo(status == ClaudeRunStatus.CANCELLED ? "CANCELLED"
                : status == ClaudeRunStatus.PROCESS_CLEANUP_FAILED ? "RESOURCE_CLEANUP" : "AGENT");
        assertThat(skipped()).hasSize(3);
        verifyNoInteractions(changes, policy, maven);
    }

    /** 取消后清除线程中断完成事实落盘，并在返回时恢复中断。 */
    @Test void persistsCancellationFactsDespiteThreadInterrupt() {
        when(claude.run(any(), any())).thenAnswer(i -> {
            Thread.currentThread().interrupt();
            return claudeResult(ClaudeRunStatus.CANCELLED);
        });
        try {
            assertThat(execute().status()).isEqualTo(TurnStatus.CANCELLED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(skipped()).hasSize(3);
        } finally { Thread.interrupted(); }
    }

    /** 路径计算故障与实际路径违规不能混用。 */
    @Test void mapsPolicyComputationFailure() {
        when(policy.evaluate(any(), any())).thenThrow(new IllegalArgumentException("bad policy"));
        assertThat(execute().failureCode()).isEqualTo("CHANGE_POLICY_EVALUATION_FAILED");
        verifyNoInteractions(maven);
    }

    /** 采集异常只归因当前采集阶段。 */
    @Test void mapsCollectionFailure() {
        when(changes.collect(any())).thenThrow(new IllegalStateException("git failed"));
        assertThat(execute().failureCode()).isEqualTo("CHANGE_COLLECTION_FAILED");
        verifyNoInteractions(policy, maven);
    }

    /** Maven 的业务失败、启动故障、取消与资源故障保留不同结论。 */
    @ParameterizedTest @EnumSource(value = MavenVerificationStatus.class, names = {"TEST_FAILED", "BUILD_FAILED", "START_FAILED", "CANCELLED", "PROCESS_CLEANUP_FAILED"})
    void mapsMavenResults(MavenVerificationStatus status) {
        when(maven.verify(any())).thenReturn(mavenResult(status));
        var result = execute();
        assertThat(result.failedStage()).isEqualTo("maven");
        assertThat(facts).noneMatch(f -> f.stage.equals("maven") && f.type == RunEventType.STAGE_COMPLETED);
        if (status == MavenVerificationStatus.TEST_FAILED || status == MavenVerificationStatus.BUILD_FAILED) {
            assertThat(result.status()).isEqualTo(TurnStatus.COMPLETED);
            assertThat(result.failureCode()).isEqualTo("MAVEN_VERIFICATION_FAILED");
        } else {
            verify(changes, times(1)).collect(any());
            assertThat(result.failureKind()).isEqualTo(status == MavenVerificationStatus.CANCELLED ? "CANCELLED"
                    : status == MavenVerificationStatus.PROCESS_CLEANUP_FAILED ? "RESOURCE_CLEANUP" : "INFRASTRUCTURE");
        }
    }

    /** 验证后实际文件变更不产生 Maven 完成事实。 */
    @Test void verificationDriftFailsBeforeStageCompletion() {
        when(maven.verify(any())).thenAnswer(i -> {
            Files.writeString(root.resolve("App.java"), "after");
            return mavenResult(MavenVerificationStatus.PASSED);
        });
        var result = execute();
        assertThat(result.failureCode()).isEqualTo("WORKSPACE_CHANGED_DURING_VERIFICATION");
        assertThat(result.failedStage()).isEqualTo("maven");
        assertThat(facts).noneMatch(f -> f.stage.equals("maven") && f.type == RunEventType.STAGE_COMPLETED);
    }

    /** 清理故障不能被随后无法保存阶段失败事件掩盖。 */
    @Test void cleanupFailureWinsOverStageNotificationFailure() {
        when(claude.run(any(), any())).thenReturn(claudeResult(ClaudeRunStatus.PROCESS_CLEANUP_FAILED));
        failStage = "coding"; failType = RunEventType.STAGE_FAILED;
        assertThat(execute().failureCode()).isEqualTo("PROCESS_CLEANUP_FAILED");
        verifyNoInteractions(changes, maven);
    }

    /** Agent 事件写入异常后仍保留 Runner 已确认的资源清理失败。 */
    @Test void agentEventFailureDoesNotHideCleanupFailure() {
        failStage = "coding"; failType = RunEventType.AGENT_EVENT_OBSERVED;
        when(claude.run(any(), any())).thenAnswer(i -> {
            AgentEventSink sink = i.getArgument(1);
            assertThatThrownBy(() -> sink.onEvent(new AgentEvent(AgentEventType.MESSAGE_COMPLETED, null,
                    mapper.createObjectNode().put("text", "原始消息")))).isInstanceOf(RuntimeException.class);
            return claudeResult(ClaudeRunStatus.PROCESS_CLEANUP_FAILED);
        });
        assertThat(execute().failureCode()).isEqualTo("PROCESS_CLEANUP_FAILED");
        assertThat(facts.getLast().type).isEqualTo(RunEventType.STAGE_STARTED);
    }

    /** 正式 Agent 映射保留原始业务文本和统一载荷。 */
    @Test void usesSharedMapperWithoutTruncatingBusinessMessage() {
        String message = "中".repeat(20000);
        when(claude.run(any(), any())).thenAnswer(i -> {
            AgentEventSink sink = i.getArgument(1);
            sink.onEvent(new AgentEvent(AgentEventType.MESSAGE_COMPLETED, "original",
                    mapper.createObjectNode().put("text", message)));
            return claudeResult(ClaudeRunStatus.COMPLETED);
        });
        assertThat(execute().assistantMessage()).isEqualTo(message);
        var payload = facts.stream().filter(f -> f.type == RunEventType.AGENT_EVENT_OBSERVED).findFirst().orElseThrow().payload;
        assertThat(payload.path("agentProtocolVersion").asInt()).isEqualTo(1);
        assertThat(payload.path("externalId").asText()).isEqualTo("original");
        assertThat(payload.has("payload")).isFalse();
    }

    /** 执行一次候选编排。 */
    private CodingTurn execute() { return orchestrator.execute(session, turn, ignored -> {}, cancellation::get).turn(); }

    /** 实际越界保留正常业务终态，但路径阶段明确失败。 */
    @Test void policyRejectionRemainsCompletedBusinessOutcome() {
        var violation = new ChangePolicyViolation("App.java", GitChangeType.MODIFIED,
                ChangePolicyViolationType.DENIED_PATH, "**", "禁止修改");
        when(policy.evaluate(any(), any())).thenReturn(new ChangePolicyResult(List.of(violation)));
        var result = execute();
        assertThat(result.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(result.outcome()).isEqualTo(TurnOutcome.POLICY_VIOLATION);
        assertThat(result.failedStage()).isEqualTo("change-policy");
        assertThat(result.failureCode()).isEqualTo("CHANGE_POLICY_REJECTED");
        assertThat(skipped()).extracting(Fact::stage).containsExactly("maven");
    }
    /** 提取尚未执行阶段的事实。 */
    private List<Fact> skipped() { return facts.stream().filter(f -> f.type == RunEventType.STAGE_SKIPPED).toList(); }
    /** 创建资源已收敛的 Agent 返回值。 */
    private ClaudeRunResult claudeResult(ClaudeRunStatus status) {
        return new ClaudeRunResult(status, 0, true, null, true,
                new AgentEvent(AgentEventType.RUNTIME_COMPLETED, null, mapper.createObjectNode()),
                Instant.now(), Instant.now(), null);
    }
    /** 创建受控 Maven 结果。 */
    private MavenVerificationResult mavenResult(MavenVerificationStatus status) {
        return new MavenVerificationResult(status, 0, List.of("mvn"), Instant.now(), Instant.now(),
                null, null, null, "验证结果", null);
    }
    private record Fact(String stage, RunEventType type, JsonNode payload) {}
}
