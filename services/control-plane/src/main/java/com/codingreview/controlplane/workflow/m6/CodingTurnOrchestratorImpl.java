package com.codingreview.controlplane.workflow.m6;

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
import com.codingreview.controlplane.verification.maven.MavenVerifier;
import com.codingreview.controlplane.verification.maven.model.MavenVerificationRequest;
import com.codingreview.controlplane.workspace.change.GitChangeCollector;
import com.codingreview.controlplane.workspace.change.WorkspaceFingerprintCalculator;
import com.codingreview.controlplane.workspace.change.model.GitChangeSet;
import com.codingreview.controlplane.workspace.change.policy.ChangePolicyEvaluator;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicyResult;
import com.codingreview.controlplane.workspace.worktree.model.WorktreeHandle;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
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

    /** 装配多轮 Coding 所需的适配器和验证器。 */
    public CodingTurnOrchestratorImpl(ClaudeCodeAdapter claude, GitChangeCollector changes,
                                      ChangePolicyEvaluator policy, MavenVerifier maven,
                                      RunEventJournal journal, ObjectMapper mapper) {
        this.claude = Objects.requireNonNull(claude, "claude");
        this.changes = Objects.requireNonNull(changes, "changes");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.maven = Objects.requireNonNull(maven, "maven");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
        this.fingerprintCalculator = new WorkspaceFingerprintCalculator();
    }

    /** 执行 Claude、采集累计 Diff、检查路径并按需运行 Maven。 */
    @Override
    public CodingTurnExecution execute(
            CodingSession session,
            CodingTurn turn,
            Consumer<CodingSession> sessionUpdateSink
    ) {
        Objects.requireNonNull(sessionUpdateSink, "sessionUpdateSink");
        WorktreeHandle handle = new WorktreeHandle(session.sourceRepository(), session.worktreePath(),
                session.sessionId().toString(), session.baseRef(), session.resolvedCommit(), session.sourceRepositoryDirty());
        List<String> assistant = new ArrayList<>();
        AtomicReference<CodingSession> currentSession = new AtomicReference<>(session);
        ClaudeConversationMode mode = session.claudeSessionId() == null
                ? ClaudeConversationMode.START_PERSISTENT : ClaudeConversationMode.RESUME_PERSISTENT;
        append(turn, RunEventType.RUN_STARTED, "Turn 开始执行。", null);
        try {
            ClaudeRunResult run = claude.run(new ClaudeRunRequest(
                    turn.turnId(), handle.worktreePath(), turn.userPrompt(), List.of(),
                    session.changePolicy().allowedPaths(), session.changePolicy().deniedPaths(),
                    mode, session.claudeSessionId()),
                    event -> observe(turn, event, assistant, currentSession, sessionUpdateSink));
            CodingSession updatedSession = currentSession.get();
            String nativeId = updatedSession.claudeSessionId() == null
                    ? run.nativeSessionId() : updatedSession.claudeSessionId();
            if (run.status() != ClaudeRunStatus.COMPLETED) {
                if (run.status() == ClaudeRunStatus.CANCELLED) {
                    CodingTurn cancelled = turn.cancelled("本轮 Coding 已停止，Worktree 修改已保留。", Instant.now());
                    appendTerminal(turn, RunEventType.RUN_CANCELLED, cancelled.userMessage());
                    return new CodingTurnExecution(updatedSession, cancelled);
                }
                CodingTurn failed = turn.failed(TurnOutcome.AGENT_FAILED, run.status().name(),
                        "Claude Code 未能完成本轮 Coding。", String.join("\n", assistant), nativeId, Instant.now());
                appendTerminal(turn, RunEventType.RUN_FAILED, failed.userMessage());
                return new CodingTurnExecution(updatedSession, failed);
            }
            GitChangeSet changeSet = changes.collect(handle);
            if (changeSet.isEmpty()) {
                CodingTurn completed = turn.complete(TurnOutcome.NO_CHANGES, 0,
                        null,
                        "本轮没有产生代码变更，未运行 Maven。", String.join("\n", assistant), nativeId, Instant.now());
                appendTerminal(turn, RunEventType.RUN_COMPLETED, completed.userMessage());
                return new CodingTurnExecution(updatedSession, completed);
            }
            String workspaceFingerprint = fingerprintCalculator.calculate(changeSet);
            ChangePolicyResult policyResult = policy.evaluate(changeSet, session.changePolicy());
            if (!policyResult.compliant()) {
                CodingTurn completed = turn.complete(TurnOutcome.POLICY_VIOLATION, changeSet.files().size(),
                        workspaceFingerprint,
                        "代码修改超出本 Session 的路径边界，Maven 已跳过。", String.join("\n", assistant), nativeId, Instant.now());
                appendTerminal(turn, RunEventType.RUN_FAILED, completed.userMessage());
                return new CodingTurnExecution(updatedSession, completed);
            }
            var verification = maven.verify(new MavenVerificationRequest(turn.turnId().toString(), handle, 1));
            TurnOutcome outcome = verification.passed() ? TurnOutcome.CHANGES_VERIFIED : TurnOutcome.VERIFICATION_FAILED;
            CodingTurn completed = turn.complete(outcome, changeSet.files().size(),
                    workspaceFingerprint, verification.userMessage(),
                    String.join("\n", assistant), nativeId, Instant.now());
            appendTerminal(turn, verification.passed() ? RunEventType.RUN_COMPLETED : RunEventType.RUN_FAILED,
                    completed.userMessage());
            return new CodingTurnExecution(updatedSession, completed);
        } catch (RuntimeException exception) {
            CodingTurn failed = turn.failed(TurnOutcome.AGENT_FAILED, "TURN_EXECUTION_FAILED",
                    "本轮执行发生系统错误，Worktree 已保留，可继续发起下一轮。", String.join("\n", assistant),
                    currentSession.get().claudeSessionId(), Instant.now());
            appendTerminal(turn, RunEventType.RUN_FAILED, failed.userMessage());
            throw new CodingTurnExecutionException(failed, exception);
        }
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

    /** 记录 Turn 终态事件。 */
    private void appendTerminal(CodingTurn turn, RunEventType type, String message) { append(turn, type, message, null); }

    /** 将异常携带已构造的失败 Turn 返回给应用层。 */
    public static final class CodingTurnExecutionException extends RuntimeException {
        private final CodingTurn failedTurn;
        public CodingTurnExecutionException(CodingTurn failedTurn, RuntimeException cause) { super(cause); this.failedTurn = failedTurn; }
        public CodingTurn failedTurn() { return failedTurn; }
    }
}
