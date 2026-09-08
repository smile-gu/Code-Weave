package com.codingreview.controlplane.workflow.coding;

import com.codingreview.controlplane.adapter.claude.ClaudeCodeAdapter;
import com.codingreview.controlplane.adapter.claude.invocation.ClaudeConversationMode;
import com.codingreview.controlplane.adapter.claude.invocation.ClaudeRunRequest;
import com.codingreview.controlplane.adapter.claude.runtime.ClaudeRunResult;
import com.codingreview.controlplane.adapter.claude.runtime.ClaudeRunStatus;
import com.codingreview.controlplane.event.AgentEvent;
import com.codingreview.controlplane.event.AgentEventType;
import com.codingreview.controlplane.observability.run.RunEventJournal;
import com.codingreview.controlplane.observability.run.RunEventType;
import com.codingreview.controlplane.session.model.CodingSession;
import com.codingreview.controlplane.session.model.CodingTurn;
import com.codingreview.controlplane.session.model.TurnOutcome;
import com.codingreview.controlplane.session.guard.SessionWorkspaceDriftException;
import com.codingreview.controlplane.session.guard.SessionWorkspaceGuard;
import com.codingreview.controlplane.runtime.process.ProcessCleanupException;
import com.codingreview.controlplane.verification.maven.MavenVerifier;
import com.codingreview.controlplane.verification.maven.model.MavenVerificationRequest;
import com.codingreview.controlplane.workspace.change.GitChangeCollector;
import com.codingreview.controlplane.workspace.change.WorkspaceFingerprintCalculator;
import com.codingreview.controlplane.workspace.change.model.GitChangeSet;
import com.codingreview.controlplane.workspace.change.policy.ChangePolicyEvaluator;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicyResult;
import com.codingreview.controlplane.workspace.model.WorkspaceHandle;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 复用现有执行基础设施，按 Turn 边界组织多轮 Coding。 */
public final class CodingTurnOrchestratorImpl implements CodingTurnOrchestrator {
    private final ClaudeCodeAdapter claude;
    private final GitChangeCollector changes;
    private final ChangePolicyEvaluator policy;
    private final MavenVerifier maven;
    private final RunEventJournal journal;
    private final ObjectMapper mapper;
    private final WorkspaceFingerprintCalculator fingerprintCalculator;
    private final SessionWorkspaceGuard workspaceGuard;

    /** 装配多轮 Coding 所需的适配器、验证器和工作区门禁。 */
    public CodingTurnOrchestratorImpl(ClaudeCodeAdapter claude, GitChangeCollector changes,
                                      ChangePolicyEvaluator policy, MavenVerifier maven,
                                      RunEventJournal journal, ObjectMapper mapper,
                                      SessionWorkspaceGuard workspaceGuard) {
        this.claude = Objects.requireNonNull(claude, "claude");
        this.changes = Objects.requireNonNull(changes, "changes");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.maven = Objects.requireNonNull(maven, "maven");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
        this.fingerprintCalculator = new WorkspaceFingerprintCalculator();
        this.workspaceGuard = Objects.requireNonNull(workspaceGuard, "workspaceGuard");
    }

    /** 执行 Claude、采集累计 Diff、检查路径并按需运行 Maven。 */
    @Override
    public CodingTurnExecution execute(
            CodingSession session,
            CodingTurn turn,
            Consumer<CodingSession> sessionUpdateSink
    ) {
        return execute(session, turn, sessionUpdateSink, () -> false);
    }

    /** 执行单轮 Coding，并在 Claude、变更采集和 Maven 阶段边界响应取消。 */
    @Override
    public CodingTurnExecution execute(
            CodingSession session,
            CodingTurn turn,
            Consumer<CodingSession> sessionUpdateSink,
            BooleanSupplier cancellationRequested
    ) {
        Objects.requireNonNull(sessionUpdateSink, "sessionUpdateSink");
        Objects.requireNonNull(cancellationRequested, "cancellationRequested");
        WorkspaceHandle workspace = new WorkspaceHandle(
                session.sourceRepository(), session.activeReviewBaselineCommit()
        );
        List<String> assistant = new ArrayList<>();
        AtomicReference<CodingSession> currentSession = new AtomicReference<>(session);
        ClaudeConversationMode mode = session.claudeSessionId() == null
                ? ClaudeConversationMode.START_PERSISTENT : ClaudeConversationMode.RESUME_PERSISTENT;
        append(turn, RunEventType.RUN_STARTED, "Turn 开始执行。", null);
        try {
            if (cancellationRequested.getAsBoolean()) {
                return cancelled(session, turn, assistant);
            }
            workspaceGuard.assertStable(session);
            ClaudeRunResult run = claude.run(new ClaudeRunRequest(
                    turn.turnId(), workspace.workspaceRoot(), turn.userPrompt(), List.of(),
                    session.changePolicy().allowedPaths(), session.changePolicy().deniedPaths(),
                    mode, session.claudeSessionId()),
                    event -> observe(turn, event, assistant, currentSession, sessionUpdateSink));
            CodingSession updatedSession = currentSession.get();
            String nativeId = updatedSession.claudeSessionId() == null
                    ? run.nativeSessionId() : updatedSession.claudeSessionId();
            if (run.status() != ClaudeRunStatus.COMPLETED) {
                if (run.status() == ClaudeRunStatus.CANCELLED) {
                    CodingTurn cancelled = turn.cancelled("本轮 Coding 已停止，真实工作区修改已保留。", Instant.now());
                    return new CodingTurnExecution(updatedSession, cancelled);
                }
                if (run.status() == ClaudeRunStatus.PROCESS_CLEANUP_FAILED) {
                    CodingTurn failed = turn.failed(
                            TurnOutcome.AGENT_FAILED,
                            "PROCESS_CLEANUP_FAILED",
                            "Claude Code 进程未能可靠清理，当前会话已停止继续执行。",
                            String.join("\n", assistant), nativeId, Instant.now()
                    );
                    return new CodingTurnExecution(updatedSession, failed);
                }
                CodingTurn failed = turn.failed(TurnOutcome.AGENT_FAILED, run.status().name(),
                        "Claude Code 未能完成本轮 Coding。", String.join("\n", assistant), nativeId, Instant.now());
                return new CodingTurnExecution(updatedSession, failed);
            }
            if (cancellationRequested.getAsBoolean()) {
                return cancelled(updatedSession, turn, assistant);
            }
            workspaceGuard.assertStable(currentSession.get());
            GitChangeSet changeSet = changes.collect(workspace);
            workspaceGuard.assertStable(currentSession.get());
            if (changeSet.isEmpty()) {
                CodingTurn completed = turn.complete(TurnOutcome.NO_CHANGES, 0,
                        null,
                        "本轮没有产生代码变更，未运行 Maven。", String.join("\n", assistant), nativeId, Instant.now());
                return new CodingTurnExecution(updatedSession, completed);
            }
            String workspaceFingerprint = fingerprintCalculator.calculate(changeSet);
            ChangePolicyResult policyResult = policy.evaluate(changeSet, session.changePolicy());
            if (!policyResult.compliant()) {
                CodingTurn completed = turn.complete(TurnOutcome.POLICY_VIOLATION, changeSet.files().size(),
                        workspaceFingerprint,
                        "代码修改超出本 Session 的路径边界，Maven 已跳过。", String.join("\n", assistant), nativeId, Instant.now());
                return new CodingTurnExecution(updatedSession, completed);
            }
            if (cancellationRequested.getAsBoolean()) {
                return cancelled(updatedSession, turn, assistant);
            }
            var verification = maven.verify(new MavenVerificationRequest(
                    turn.turnId().toString(), workspace.workspaceRoot(), 1
            ));
            if (verification.status()
                    == com.codingreview.controlplane.verification.maven.model.MavenVerificationStatus.CANCELLED) {
                return cancelled(updatedSession, turn, assistant);
            }
            if (verification.status()
                    == com.codingreview.controlplane.verification.maven.model.MavenVerificationStatus.PROCESS_CLEANUP_FAILED) {
                CodingTurn failed = turn.failed(
                        TurnOutcome.AGENT_FAILED,
                        "PROCESS_CLEANUP_FAILED",
                        "Maven 进程未能可靠清理，当前会话已停止继续执行。",
                        String.join("\n", assistant), nativeId, Instant.now()
                );
                return new CodingTurnExecution(updatedSession, failed);
            }
            if (cancellationRequested.getAsBoolean()) {
                return cancelled(updatedSession, turn, assistant);
            }
            workspaceGuard.assertStable(currentSession.get());
            GitChangeSet verifiedChanges = changes.collect(workspace);
            String verifiedFingerprint = fingerprintCalculator.calculate(verifiedChanges);
            if (!workspaceFingerprint.equals(verifiedFingerprint)) {
                CodingTurn failed = turn.failed(
                        TurnOutcome.WORKSPACE_DRIFT,
                        "WORKSPACE_CHANGED_DURING_VERIFICATION",
                        "Maven 验证期间工作区发生变化，本次验证结论已失效，请稳定代码后重新验证。",
                        String.join("\n", assistant), nativeId, Instant.now()
                );
                return new CodingTurnExecution(updatedSession, failed);
            }
            TurnOutcome outcome = verification.passed() ? TurnOutcome.CHANGES_VERIFIED : TurnOutcome.VERIFICATION_FAILED;
            CodingTurn completed = turn.complete(outcome, changeSet.files().size(),
                    workspaceFingerprint, verification.userMessage(),
                    String.join("\n", assistant), nativeId, Instant.now());
            return new CodingTurnExecution(updatedSession, completed);
        } catch (SessionWorkspaceDriftException exception) {
            CodingTurn failed = turn.failed(TurnOutcome.WORKSPACE_DRIFT, exception.code(),
                    exception.getMessage(), String.join("\n", assistant),
                    currentSession.get().claudeSessionId(), Instant.now());
            return new CodingTurnExecution(currentSession.get(), failed);
        } catch (ProcessCleanupException exception) {
            CodingTurn failed = turn.failed(
                    TurnOutcome.AGENT_FAILED,
                    "PROCESS_CLEANUP_FAILED",
                    "执行进程未能可靠清理，当前会话已停止继续执行。",
                    String.join("\n", assistant), currentSession.get().claudeSessionId(), Instant.now()
            );
            return new CodingTurnExecution(currentSession.get(), failed);
        } catch (RuntimeException exception) {
            CodingTurn failed = turn.failed(TurnOutcome.AGENT_FAILED, "TURN_EXECUTION_FAILED",
                    "本轮执行发生系统错误，真实工作区修改已保留，可继续发起下一轮。", String.join("\n", assistant),
                    currentSession.get().claudeSessionId(), Instant.now());
            throw new CodingTurnExecutionException(failed, exception);
        }
    }

    /** 生成保留真实工作区修改的取消候选结果。 */
    private CodingTurnExecution cancelled(
            CodingSession session,
            CodingTurn turn,
            List<String> assistant
    ) {
        return new CodingTurnExecution(session, turn.cancelled(
                "本轮 Coding 已停止，真实工作区修改已保留。", Instant.now()
        ));
    }

    /** 将 Agent 事件写入统一 Run 事实日志，并提取可读助手文本。 */
    private void observe(
            CodingTurn turn,
            AgentEvent event,
            List<String> assistant,
            AtomicReference<CodingSession> currentSession,
            Consumer<CodingSession> sessionUpdateSink
    ) {
        if (event.type() == AgentEventType.RUNTIME_INITIALIZED
                && currentSession.get().claudeSessionId() == null) {
            if (event.externalId() == null || event.externalId().isBlank()) {
                throw new IllegalStateException("Claude Code 初始化事件缺少原生会话标识。");
            }
            CodingSession updated = currentSession.get().bindClaudeSession(
                    event.externalId(), Instant.now());
            sessionUpdateSink.accept(updated);
            currentSession.set(updated);
        }
        if (event.type() == AgentEventType.MESSAGE_COMPLETED) {
            String text = event.payload().path("text").asText(null);
            if (text != null && !text.isBlank()) assistant.add(text);
        }
        journal.append(turn.turnId().toString(), "coding-turn", 1, RunEventType.AGENT_EVENT_OBSERVED,
                mapper.createObjectNode().put("eventType", event.type().name()).set("payload", event.payload()));
    }

    /** 记录 Turn 级生命周期事件。 */
    private void append(CodingTurn turn, RunEventType type, String message, ObjectNode details) {
        ObjectNode payload = mapper.createObjectNode().put("turnId", turn.turnId().toString()).put("message", message);
        if (details != null) payload.setAll(details);
        journal.append(turn.turnId().toString(), "coding-turn", 1, type, payload);
    }

    /** 将异常携带已构造的失败 Turn 返回给应用层。 */
    public static final class CodingTurnExecutionException extends RuntimeException {
        private final CodingTurn failedTurn;
        public CodingTurnExecutionException(CodingTurn failedTurn, RuntimeException cause) { super(cause); this.failedTurn = failedTurn; }
        public CodingTurn failedTurn() { return failedTurn; }
    }
}
