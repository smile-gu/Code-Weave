package com.codingreview.controlplane.workflow.coding;

import com.codingreview.controlplane.adapter.claude.ClaudeCodeAdapter;
import com.codingreview.controlplane.adapter.claude.invocation.ClaudeConversationMode;
import com.codingreview.controlplane.adapter.claude.invocation.ClaudeRunRequest;
import com.codingreview.controlplane.adapter.claude.runtime.ClaudeRunResult;
import com.codingreview.controlplane.adapter.claude.runtime.ClaudeRunStatus;
import com.codingreview.controlplane.observability.agent.AgentEventPayloadMapper;
import com.codingreview.controlplane.observability.run.error.RunEventWriteException;
import com.codingreview.controlplane.workflow.coding.stage.CodingStage;
import com.codingreview.controlplane.workflow.coding.stage.CodingStageLifecycle;
import com.codingreview.controlplane.workflow.coding.stage.CodingStageLifecycle.StageEventPersistenceException;
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
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(CodingTurnOrchestratorImpl.class);
    private final ClaudeCodeAdapter claude;
    private final GitChangeCollector changes;
    private final ChangePolicyEvaluator policy;
    private final MavenVerifier maven;
    private final RunEventJournal journal;
    private final ObjectMapper mapper;
    private final WorkspaceFingerprintCalculator fingerprintCalculator;
    private final SessionWorkspaceGuard workspaceGuard;
    private final AgentEventPayloadMapper agentMapper;

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
        this.agentMapper = new AgentEventPayloadMapper(mapper);
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
        CodingStageLifecycle stages = new CodingStageLifecycle(turn.turnId().toString(), journal, mapper);
        boolean interrupted = false;
        try {
            try {
                append(turn, RunEventType.RUN_STARTED, "Turn 开始执行。", null);
            } catch (RuntimeException exception) {
                stages.markWriteFailed();
                throw new StageEventPersistenceException(exception);
            }
            checkCancelled(cancellationRequested);
            stages.start(CodingStage.WORKSPACE);
            workspaceGuard.assertStable(session);
            stages.complete("工作区分支与基线检查通过。");

            checkCancelled(cancellationRequested);
            stages.start(CodingStage.CODING);
            ClaudeRunResult run = claude.run(new ClaudeRunRequest(
                    turn.turnId(), workspace.workspaceRoot(), turn.userPrompt(), List.of(),
                    session.changePolicy().allowedPaths(), session.changePolicy().deniedPaths(),
                    mode, session.claudeSessionId()),
                    event -> {
                        try {
                            observe(turn, event, assistant, currentSession, sessionUpdateSink);
                        } catch (RunEventWriteException exception) {
                            stages.markWriteFailed();
                            throw exception;
                        }
                    });
            // Runner 返回后资源已经收敛，清除中断以允许阶段事实落盘。
            interrupted |= Thread.interrupted();
            if (run.status() == ClaudeRunStatus.PROCESS_CLEANUP_FAILED) {
                throw failure("PROCESS_CLEANUP_FAILED", "RESOURCE_CLEANUP", run.status().name(),
                        "Claude Code 进程未能可靠清理。");
            }
            if (stages.writeFailed() || run.status() == ClaudeRunStatus.EVENT_PUBLISH_FAILED) {
                throw new StageEventPersistenceException(null);
            }
            if (run.status() == ClaudeRunStatus.CANCELLED) throw cancelledFailure();
            if (run.status() != ClaudeRunStatus.COMPLETED) {
                throw failure("AGENT_EXECUTION_FAILED", "AGENT", run.status().name(),
                        "Claude Code 未能完成本轮 Coding。");
            }
            checkCancelled(cancellationRequested);
            stages.complete("Agent 执行结束，已确认本轮进程资源释放。");
            CodingSession updatedSession = currentSession.get();
            String nativeId = updatedSession.claudeSessionId() == null
                    ? run.nativeSessionId() : updatedSession.claudeSessionId();

            checkCancelled(cancellationRequested);
            stages.start(CodingStage.CHANGE_COLLECTION);
            workspaceGuard.assertStable(updatedSession);
            GitChangeSet changeSet = changes.collect(workspace);
            String workspaceFingerprint = fingerprintCalculator.calculate(changeSet);
            workspaceGuard.assertStable(updatedSession);
            checkCancelled(cancellationRequested);
            stages.complete("累计变更采集完成，共 " + changeSet.files().size() + " 个文件。");
            if (changeSet.isEmpty()) {
                stages.skipRemaining("NO_CHANGES", "没有代码修改，无需执行路径门禁和 Maven。", CodingStage.CHANGE_COLLECTION);
                return new CodingTurnExecution(updatedSession, turn.complete(TurnOutcome.NO_CHANGES, 0,
                        null, "本轮没有产生代码变更，未运行 Maven。",
                        String.join("\n", assistant), nativeId, Instant.now()));
            }

            checkCancelled(cancellationRequested);
            stages.start(CodingStage.CHANGE_POLICY);
            ChangePolicyResult policyResult = policy.evaluate(changeSet, session.changePolicy());
            checkCancelled(cancellationRequested);
            if (!policyResult.compliant()) {
                stages.fail("CHANGE_POLICY_REJECTED", "POLICY", "累计修改超出允许路径。", null);
                stages.skipRemaining("UPSTREAM_FAILED", "路径门禁未通过，未运行 Maven。", CodingStage.CHANGE_POLICY);
                return new CodingTurnExecution(updatedSession, turn.complete(TurnOutcome.POLICY_VIOLATION,
                        changeSet.files().size(), workspaceFingerprint, "代码修改超出本 Session 的路径边界，Maven 已跳过。",
                        String.join("\n", assistant), nativeId, Instant.now())
                        .withDiagnostic(CodingStage.CHANGE_POLICY.id(), "POLICY", "CHANGE_POLICY_REJECTED"));
            }
            stages.complete("累计修改均符合路径约束。");

            checkCancelled(cancellationRequested);
            stages.start(CodingStage.MAVEN);
            workspaceGuard.assertStable(updatedSession);
            var verification = maven.verify(new MavenVerificationRequest(
                    turn.turnId().toString(), workspace.workspaceRoot(), 1));
            interrupted |= Thread.interrupted();
            if (verification.status() == com.codingreview.controlplane.verification.maven.model.MavenVerificationStatus.PROCESS_CLEANUP_FAILED) {
                throw failure("PROCESS_CLEANUP_FAILED", "RESOURCE_CLEANUP", verification.status().name(),
                        "Maven 进程未能可靠清理。");
            }
            if (verification.status() == com.codingreview.controlplane.verification.maven.model.MavenVerificationStatus.CANCELLED) {
                throw cancelledFailure();
            }
            checkCancelled(cancellationRequested);
            switch (verification.status()) {
                case PASSED, BUILD_FAILED, TEST_FAILED -> { }
                default -> throw failure("MAVEN_EXECUTION_FAILED", "INFRASTRUCTURE", verification.status().name(),
                        verification.userMessage());
            }
            workspaceGuard.assertStable(updatedSession);
            GitChangeSet verifiedChanges = changes.collect(workspace);
            String verifiedFingerprint = fingerprintCalculator.calculate(verifiedChanges);
            workspaceGuard.assertStable(updatedSession);
            checkCancelled(cancellationRequested);
            if (!workspaceFingerprint.equals(verifiedFingerprint)) {
                throw failure("WORKSPACE_CHANGED_DURING_VERIFICATION", "WORKSPACE_DRIFT", null,
                        "Maven 验证期间工作区发生变化，本次验证结论已失效。");
            }
            if (!verification.passed()) {
                stages.fail("MAVEN_VERIFICATION_FAILED", "VERIFICATION", verification.userMessage(), verification.status().name());
            } else {
                stages.complete("Maven 验证通过，验证前后工作区指纹一致。");
            }
            CodingTurn completed = turn.complete(verification.passed() ? TurnOutcome.CHANGES_VERIFIED : TurnOutcome.VERIFICATION_FAILED,
                    changeSet.files().size(), workspaceFingerprint, verification.userMessage(),
                    String.join("\n", assistant), nativeId, Instant.now());
            if (!verification.passed()) completed = completed.withDiagnostic("maven", "VERIFICATION", "MAVEN_VERIFICATION_FAILED");
            return new CodingTurnExecution(updatedSession, completed);
        } catch (RuntimeException exception) {
            interrupted |= Thread.interrupted();
            CodingStage stage = stages.current();
            StageFailure failure = classify(exception, stage);
            if (!(exception instanceof StageFailure)) {
                LOGGER.warn("Coding 阶段执行异常，turnId={}, stage={}, code={}",
                        turn.turnId(), stage == null ? null : stage.id(), failure.code, exception);
            }
            if (exception instanceof StageEventPersistenceException || exception instanceof RunEventWriteException) stages.markWriteFailed();
            if (!stages.writeFailed()) {
                try {
                    if (stage != null) stages.fail(failure.code, failure.kind, failure.getMessage(), failure.causeCode);
                    stages.skipRemaining("CANCELLED".equals(failure.kind) ? "TURN_CANCELLED" : "UPSTREAM_FAILED",
                            "CANCELLED".equals(failure.kind) ? "本轮已停止，后续阶段未执行。" : "上游未完成，后续阶段未执行。", stage);
                } catch (StageEventPersistenceException writeFailure) {
                    if (!"RESOURCE_CLEANUP".equals(failure.kind)) failure = classify(writeFailure, stage);
                    // 清理失败不能被随后出现的日志故障掩盖。
                }
            }
            CodingTurn candidate;
            if ("CANCELLED".equals(failure.kind)) {
                candidate = turn.cancelled(failure.getMessage(), Instant.now());
            } else {
                candidate = turn.failed("WORKSPACE_DRIFT".equals(failure.kind) ? TurnOutcome.WORKSPACE_DRIFT : TurnOutcome.AGENT_FAILED,
                        failure.code, failure.getMessage(), String.join("\n", assistant),
                        currentSession.get().claudeSessionId(), Instant.now());
            }
            candidate = candidate.withDiagnostic(stage == null ? null : stage.id(), failure.kind, failure.code);
            return new CodingTurnExecution(currentSession.get(), candidate);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** 在阶段边界响应明确取消信号，不将任意线程中断伪装为用户取消。 */
    private void checkCancelled(BooleanSupplier cancellationRequested) {
        if (cancellationRequested.getAsBoolean()) throw cancelledFailure();
    }

    /** 生成资源确认释放后的停止诊断。 */
    private StageFailure cancelledFailure() {
        return failure("CANCELLED", "CANCELLED", null, "本轮 Coding 已停止，真实工作区修改已保留。");
    }

    /** 将底层异常映射为固定协议，不公开异常类名。 */
    private StageFailure classify(RuntimeException exception, CodingStage stage) {
        if (exception instanceof StageFailure failure) return failure;
        if (exception instanceof ProcessCleanupException) return failure("PROCESS_CLEANUP_FAILED", "RESOURCE_CLEANUP", null, "执行资源未能确认释放，当前会话保持执行保护。");
        if (exception instanceof StageEventPersistenceException || exception instanceof RunEventWriteException) {
            return failure("EXECUTION_EVENT_PERSISTENCE_FAILED", "INFRASTRUCTURE", null, "执行事实无法可靠保存，本轮已停止。");
        }
        if (exception instanceof SessionWorkspaceDriftException drift) return failure(drift.code(), "WORKSPACE_DRIFT", null, drift.getMessage());
        String code = stage == null ? "TURN_EXECUTION_FAILED" : switch (stage) {
            case WORKSPACE -> "WORKSPACE_CHECK_FAILED";
            case CODING -> "AGENT_EXECUTION_FAILED";
            case CHANGE_COLLECTION -> "CHANGE_COLLECTION_FAILED";
            case CHANGE_POLICY -> "CHANGE_POLICY_EVALUATION_FAILED";
            case MAVEN -> "MAVEN_EXECUTION_FAILED";
        };
        return failure(code, "INFRASTRUCTURE", null, "执行发生系统错误，真实工作区修改已保留。");
    }

    /** 构造内部控制流使用的阶段失败，不触发 Turn 终态写入。 */
    private StageFailure failure(String code, String kind, String causeCode, String message) {
        return new StageFailure(code, kind, causeCode, message);
    }

    /** 携带候选诊断以统一结束当前阶段及未执行阶段。 */
    private static final class StageFailure extends RuntimeException {
        private final String code;
        private final String kind;
        private final String causeCode;
        private StageFailure(String code, String kind, String causeCode, String message) {
            super(message); this.code = code; this.kind = kind; this.causeCode = causeCode;
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
        try {
            journal.append(turn.turnId().toString(), "coding", 1, RunEventType.AGENT_EVENT_OBSERVED, agentMapper.map(event));
        } catch (RuntimeException exception) {
            throw new RunEventWriteException("Agent 执行事实无法可靠保存。", null, exception);
        }
    }

    /** 记录 Turn 级生命周期事件。 */
    private void append(CodingTurn turn, RunEventType type, String message, ObjectNode details) {
        ObjectNode payload = mapper.createObjectNode().put("turnId", turn.turnId().toString()).put("message", message);
        if (details != null) payload.setAll(details);
        journal.append(turn.turnId().toString(), "coding-turn", 1, type, payload);
    }

}
