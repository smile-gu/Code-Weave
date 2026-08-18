package com.codingreview.controlplane.workflow.m0;

import com.codingreview.controlplane.adapter.claude.ClaudeCodeAdapter;
import com.codingreview.controlplane.adapter.claude.invocation.ClaudeRunRequest;
import com.codingreview.controlplane.adapter.claude.runtime.ClaudeRunStatus;
import com.codingreview.controlplane.observability.run.RunEventJournal;
import com.codingreview.controlplane.observability.run.RunEventType;
import com.codingreview.controlplane.observability.run.error.RunEventReadException;
import com.codingreview.controlplane.observability.run.error.RunEventWriteException;
import com.codingreview.controlplane.verification.maven.MavenVerifier;
import com.codingreview.controlplane.verification.maven.model.MavenVerificationRequest;
import com.codingreview.controlplane.verification.maven.model.MavenVerificationStatus;
import com.codingreview.controlplane.workflow.m0.event.AgentRunEventMapper;
import com.codingreview.controlplane.workflow.m0.model.M0RunFailureCode;
import com.codingreview.controlplane.workflow.m0.model.M0RunRequest;
import com.codingreview.controlplane.workflow.m0.model.M0RunResult;
import com.codingreview.controlplane.workflow.m0.model.M0RunStage;
import com.codingreview.controlplane.workflow.m0.model.M0RunStatus;
import com.codingreview.controlplane.workspace.change.GitChangeCollector;
import com.codingreview.controlplane.workspace.change.error.GitChangeException;
import com.codingreview.controlplane.workspace.change.policy.ChangePolicyEvaluator;
import com.codingreview.controlplane.workspace.worktree.GitWorktreeManager;
import com.codingreview.controlplane.workspace.worktree.error.WorktreeException;
import com.codingreview.controlplane.workspace.worktree.model.WorktreeCreateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Objects;

/** 以显式顺序和严格门禁执行 M0 Coding 主链路。 */
public final class M0RunOrchestratorImpl implements M0RunOrchestrator {

    private static final int FIRST_ATTEMPT = 1;

    private final GitWorktreeManager worktreeManager;
    private final ClaudeCodeAdapter claudeCodeAdapter;
    private final GitChangeCollector changeCollector;
    private final ChangePolicyEvaluator changePolicyEvaluator;
    private final MavenVerifier mavenVerifier;
    private final RunEventJournal eventJournal;
    private final AgentRunEventMapper agentEventMapper;
    private final ObjectMapper objectMapper;
    private final StageLifecycle stageLifecycle;

    public M0RunOrchestratorImpl(
            GitWorktreeManager worktreeManager,
            ClaudeCodeAdapter claudeCodeAdapter,
            GitChangeCollector changeCollector,
            ChangePolicyEvaluator changePolicyEvaluator,
            MavenVerifier mavenVerifier,
            RunEventJournal eventJournal,
            ObjectMapper objectMapper
    ) {
        this.worktreeManager = Objects.requireNonNull(worktreeManager, "worktreeManager");
        this.claudeCodeAdapter = Objects.requireNonNull(claudeCodeAdapter, "claudeCodeAdapter");
        this.changeCollector = Objects.requireNonNull(changeCollector, "changeCollector");
        this.changePolicyEvaluator = Objects.requireNonNull(
                changePolicyEvaluator, "changePolicyEvaluator"
        );
        this.mavenVerifier = Objects.requireNonNull(mavenVerifier, "mavenVerifier");
        this.eventJournal = Objects.requireNonNull(eventJournal, "eventJournal");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        this.agentEventMapper = new AgentRunEventMapper(this.objectMapper);
        this.stageLifecycle = new StageLifecycle(this.eventJournal, this.objectMapper);
    }

    /** 依次执行五个阶段并在任一失败时立即短路。 */
    @Override
    public M0RunResult execute(M0RunRequest request) {
        Objects.requireNonNull(request, "request");
        RunProgress progress = new RunProgress(request, Instant.now());

        M0RunResult failure = executeWorktree(progress);
        if (failure != null) {
            return failure;
        }
        failure = executeCoding(progress);
        if (failure != null) {
            return failure;
        }
        failure = executeChangeCollection(progress);
        if (failure != null) {
            return failure;
        }
        failure = executeChangePolicy(progress);
        if (failure != null) {
            return failure;
        }
        failure = executeMaven(progress);
        if (failure != null) {
            return failure;
        }

        ObjectNode completedPayload = objectMapper.createObjectNode()
                .put("changedFiles", progress.changes.files().size())
                .put("mavenStatus", progress.maven.status().name())
                .put("worktree", progress.worktree.handle().worktreePath().toString());
        eventJournal.append(
                request.runIdValue(), "run", FIRST_ATTEMPT,
                RunEventType.RUN_COMPLETED, completedPayload
        );
        return progress.completed();
    }

    /** 创建隔离 Worktree 并记录仓库相关提示。 */
    private M0RunResult executeWorktree(RunProgress progress) {
        M0RunRequest request = progress.request;
        M0RunStage stage = M0RunStage.WORKTREE;
        stageLifecycle.started(request.runIdValue(), stage,
                objectMapper.createObjectNode()
                        .put("repository", request.repository().toString())
                        .put("baseRef", request.baseRef()));
        try {
            progress.worktree = worktreeManager.create(new WorktreeCreateRequest(
                    request.repository(), request.runIdValue(), request.baseRef()
            ));
            progress.worktree.notices().forEach(notice -> eventJournal.append(
                    request.runIdValue(), stage.eventName(), FIRST_ATTEMPT, RunEventType.RUN_NOTICE,
                    objectMapper.createObjectNode()
                            .put("code", notice.type().name())
                            .put("message", notice.message())
            ));
            stageLifecycle.completed(request.runIdValue(), stage,
                    objectMapper.createObjectNode()
                            .put("worktree", progress.worktree.handle().worktreePath().toString())
                            .put("resolvedCommit", progress.worktree.handle().resolvedCommit())
                            .put("notices", progress.worktree.notices().size()));
            return null;
        } catch (RunEventWriteException | RunEventReadException exception) {
            throw exception;
        } catch (WorktreeException exception) {
            return fail(progress, stage, M0RunFailureCode.WORKTREE_FAILED,
                    exception.userMessage(), exception.diagnostic(), null);
        } catch (RuntimeException exception) {
            return internalFailure(progress, stage, exception);
        }
    }

    /** 在隔离 Worktree 中调用 Claude Code 完成代码修改。 */
    private M0RunResult executeCoding(RunProgress progress) {
        M0RunRequest request = progress.request;
        M0RunStage stage = M0RunStage.CODING;
        stageLifecycle.started(request.runIdValue(), stage,
                objectMapper.createObjectNode()
                        .put("worktree", progress.worktree.handle().worktreePath().toString()));
        try {
            ClaudeRunRequest claudeRequest = new ClaudeRunRequest(
                    request.runId(),
                    progress.worktree.handle().worktreePath(),
                    request.taskPrompt(),
                    request.acceptanceCriteria(),
                    request.changePolicy().allowedPaths(),
                    request.changePolicy().deniedPaths()
            );
            progress.claude = claudeCodeAdapter.run(claudeRequest, event -> eventJournal.append(
                    request.runIdValue(),
                    stage.eventName(),
                    FIRST_ATTEMPT,
                    RunEventType.AGENT_EVENT_OBSERVED,
                    agentEventMapper.map(event)
            ));
            if (progress.claude.status() == ClaudeRunStatus.EVENT_PUBLISH_FAILED) {
                // 当前 Sink 就是 RunEventJournal，因此该状态必须按事实源故障直接上抛。
                throw new RunEventWriteException(
                        "运行事件无法可靠保存，当前任务必须停止。",
                        progress.claude.diagnostic(),
                        null
                );
            }
            if (progress.claude.status() != ClaudeRunStatus.COMPLETED) {
                boolean cancelled = progress.claude.status() == ClaudeRunStatus.CANCELLED;
                return fail(
                        progress,
                        stage,
                        cancelled ? M0RunFailureCode.CANCELLED : M0RunFailureCode.CODING_FAILED,
                        cancelled ? "Coding 任务已取消，Worktree 已保留。"
                                : "Claude Code 未能成功完成 Coding 阶段，Worktree 已保留。",
                        progress.claude.diagnostic(),
                        objectMapper.createObjectNode()
                                .put("claudeStatus", progress.claude.status().name())
                                .put("exitCode", progress.claude.exitCode()),
                        cancelled ? M0RunStatus.CANCELLED : M0RunStatus.FAILED
                );
            }
            stageLifecycle.completed(request.runIdValue(), stage,
                    objectMapper.createObjectNode()
                            .put("claudeStatus", progress.claude.status().name())
                            .put("exitCode", progress.claude.exitCode()));
            return null;
        } catch (RunEventWriteException | RunEventReadException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            return internalFailure(progress, stage, exception);
        }
    }

    /** 采集 Claude Code 实际产生的 Git 文件变更。 */
    private M0RunResult executeChangeCollection(RunProgress progress) {
        M0RunRequest request = progress.request;
        M0RunStage stage = M0RunStage.CHANGE_COLLECTION;
        stageLifecycle.started(request.runIdValue(), stage, objectMapper.createObjectNode());
        try {
            progress.changes = changeCollector.collect(progress.worktree.handle());
            stageLifecycle.completed(request.runIdValue(), stage,
                    objectMapper.createObjectNode()
                            .put("changedFiles", progress.changes.files().size())
                            .put("diffBytes", progress.changes.unifiedDiffBytes())
                            .set("files", objectMapper.valueToTree(progress.changes.files())));
            return null;
        } catch (RunEventWriteException | RunEventReadException exception) {
            throw exception;
        } catch (GitChangeException exception) {
            return fail(progress, stage, M0RunFailureCode.CHANGE_COLLECTION_FAILED,
                    exception.userMessage(), exception.diagnostic(), null);
        } catch (RuntimeException exception) {
            return internalFailure(progress, stage, exception);
        }
    }

    /** 检查实际文件变更是否满足任务路径策略。 */
    private M0RunResult executeChangePolicy(RunProgress progress) {
        M0RunRequest request = progress.request;
        M0RunStage stage = M0RunStage.CHANGE_POLICY;
        stageLifecycle.started(request.runIdValue(), stage, objectMapper.createObjectNode());
        if (progress.changes.isEmpty()) {
            return fail(
                    progress,
                    stage,
                    M0RunFailureCode.NO_CHANGES,
                    "Claude Code 没有产生任何 Git 代码变更，Maven 验证已跳过。",
                    "GitChangeSet is empty",
                    objectMapper.createObjectNode().put("changedFiles", 0)
            );
        }
        try {
            progress.changePolicy = changePolicyEvaluator.evaluate(
                    progress.changes, request.changePolicy()
            );
            if (!progress.changePolicy.compliant()) {
                return fail(
                        progress,
                        stage,
                        M0RunFailureCode.CHANGE_POLICY_REJECTED,
                        "代码修改超出本次任务允许范围，Maven 验证已跳过。",
                        "Change policy rejected " + progress.changePolicy.violations().size()
                                + " violation(s)",
                        objectMapper.createObjectNode()
                                .put("violations", progress.changePolicy.violations().size())
                                .set("details", objectMapper.valueToTree(
                                        progress.changePolicy.violations()
                                ))
                );
            }
            stageLifecycle.completed(request.runIdValue(), stage,
                    objectMapper.createObjectNode().put("violations", 0));
            return null;
        } catch (RunEventWriteException | RunEventReadException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            return internalFailure(progress, stage, exception);
        }
    }

    /** 独立执行 Maven 编译与测试验证。 */
    private M0RunResult executeMaven(RunProgress progress) {
        M0RunRequest request = progress.request;
        M0RunStage stage = M0RunStage.MAVEN;
        stageLifecycle.started(request.runIdValue(), stage, objectMapper.createObjectNode());
        try {
            progress.maven = mavenVerifier.verify(new MavenVerificationRequest(
                    request.runIdValue(), progress.worktree.handle(), FIRST_ATTEMPT
            ));
            if (!progress.maven.passed()) {
                boolean cancelled = progress.maven.status() == MavenVerificationStatus.CANCELLED;
                return fail(
                        progress,
                        stage,
                        cancelled ? M0RunFailureCode.CANCELLED : M0RunFailureCode.MAVEN_FAILED,
                        progress.maven.userMessage(),
                        progress.maven.diagnostic(),
                        objectMapper.createObjectNode()
                                .put("mavenStatus", progress.maven.status().name())
                                .put("exitCode", progress.maven.exitCode()),
                        cancelled ? M0RunStatus.CANCELLED : M0RunStatus.FAILED
                );
            }
            stageLifecycle.completed(request.runIdValue(), stage,
                    objectMapper.createObjectNode()
                            .put("mavenStatus", progress.maven.status().name())
                            .put("tests", progress.maven.testSummary() == null
                                    ? 0 : progress.maven.testSummary().tests()));
            return null;
        } catch (RunEventWriteException | RunEventReadException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            return internalFailure(progress, stage, exception);
        }
    }

    /** 将未分类运行时异常转换为当前阶段的内部失败。 */
    private M0RunResult internalFailure(
            RunProgress progress,
            M0RunStage stage,
            RuntimeException exception
    ) {
        return fail(
                progress,
                stage,
                M0RunFailureCode.INTERNAL_ERROR,
                "系统在 " + stage.eventName() + " 阶段发生内部错误，任务已停止，Worktree 将被保留。",
                exception.getClass().getSimpleName() + ": " + exception.getMessage(),
                null
        );
    }

    /** 使用默认失败状态结束当前 Run。 */
    private M0RunResult fail(
            RunProgress progress,
            M0RunStage stage,
            M0RunFailureCode failureCode,
            String userMessage,
            String diagnostic,
            ObjectNode details
    ) {
        return fail(progress, stage, failureCode, userMessage, diagnostic, details, M0RunStatus.FAILED);
    }

    /** 发布阶段和 Run 终态事件并构造失败结果。 */
    private M0RunResult fail(
            RunProgress progress,
            M0RunStage stage,
            M0RunFailureCode failureCode,
            String userMessage,
            String diagnostic,
            ObjectNode details,
            M0RunStatus status
    ) {
        stageLifecycle.failed(
                progress.request.runIdValue(), stage, failureCode,
                userMessage, diagnostic, details
        );
        ObjectNode payload = objectMapper.createObjectNode()
                .put("status", status.name())
                .put("failedStage", stage.name())
                .put("failureCode", failureCode.name())
                .put("message", userMessage);
        if (diagnostic == null || diagnostic.isBlank()) {
            payload.putNull("diagnostic");
        } else {
            payload.put("diagnostic", diagnostic);
        }
        eventJournal.append(
                progress.request.runIdValue(), "run", FIRST_ATTEMPT,
                status == M0RunStatus.CANCELLED
                        ? RunEventType.RUN_CANCELLED : RunEventType.RUN_FAILED,
                payload
        );
        return progress.failed(status, stage, failureCode, userMessage, diagnostic);
    }

    private static final class RunProgress {
        private final M0RunRequest request;
        private final Instant startedAt;
        private com.codingreview.controlplane.workspace.worktree.model.WorktreeCreateResult worktree;
        private com.codingreview.controlplane.adapter.claude.runtime.ClaudeRunResult claude;
        private com.codingreview.controlplane.workspace.change.model.GitChangeSet changes;
        private com.codingreview.controlplane.workspace.change.policy.model.ChangePolicyResult changePolicy;
        private com.codingreview.controlplane.verification.maven.model.MavenVerificationResult maven;

        private RunProgress(M0RunRequest request, Instant startedAt) {
            this.request = request;
            this.startedAt = startedAt;
        }

        /** 汇总五个阶段的产出并创建成功结果。 */
        private M0RunResult completed() {
            return new M0RunResult(
                    request.runId(), M0RunStatus.COMPLETED, null, null,
                    worktree, claude, changes, changePolicy, maven,
                    startedAt, Instant.now(),
                    "Coding 主链路已完成，Worktree 已保留，等待用户检查代码变更。",
                    null
            );
        }

        /** 保留已完成阶段的产出并创建失败结果。 */
        private M0RunResult failed(
                M0RunStatus status,
                M0RunStage stage,
                M0RunFailureCode failureCode,
                String userMessage,
                String diagnostic
        ) {
            return new M0RunResult(
                    request.runId(), status, stage, failureCode,
                    worktree, claude, changes, changePolicy, maven,
                    startedAt, Instant.now(), userMessage, diagnostic
            );
        }
    }
}
