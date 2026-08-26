package com.codingreview.controlplane.application.session;

import com.codingreview.controlplane.session.integration.model.CodingSessionIntegration;
import com.codingreview.controlplane.application.session.error.CodingSessionConflictException;
import com.codingreview.controlplane.session.integration.model.IntegrationStatus;
import com.codingreview.controlplane.session.integration.persistence.CodingSessionIntegrationRepository;
import com.codingreview.controlplane.session.model.CodingSession;
import com.codingreview.controlplane.session.model.CodingSessionStatus;
import com.codingreview.controlplane.session.model.CodingTurn;
import com.codingreview.controlplane.session.model.TurnOutcome;
import com.codingreview.controlplane.session.model.TurnStatus;
import com.codingreview.controlplane.session.persistence.CodingSessionRepository;
import com.codingreview.controlplane.session.persistence.CodingTurnRepository;
import com.codingreview.controlplane.workspace.change.GitChangeCollector;
import com.codingreview.controlplane.workspace.change.WorkspaceFingerprintCalculator;
import com.codingreview.controlplane.workspace.change.model.GitChangeSet;
import com.codingreview.controlplane.workspace.model.WorkspaceHandle;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 对已验证 Session 执行可恢复的严格 Fast-forward 合并。 */
@Service
public final class CodingSessionIntegrationService {
    private static final long GIT_TIMEOUT_SECONDS = 30;

    private final CodingSessionRepository sessions;
    private final CodingTurnRepository turns;
    private final CodingSessionIntegrationRepository integrations;
    private final GitChangeCollector changes;
    private final WorkspaceFingerprintCalculator fingerprints;
    private final ConcurrentHashMap<UUID, Object> sessionLocks = new ConcurrentHashMap<>();

    /** 装配 Session、Turn、合并事实和 Git 变更读取边界。 */
    public CodingSessionIntegrationService(
            CodingSessionRepository sessions,
            CodingTurnRepository turns,
            CodingSessionIntegrationRepository integrations,
            GitChangeCollector changes
    ) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.turns = Objects.requireNonNull(turns, "turns");
        this.integrations = Objects.requireNonNull(integrations, "integrations");
        this.changes = Objects.requireNonNull(changes, "changes");
        this.fingerprints = new WorkspaceFingerprintCalculator();
    }

    /** 重新读取当前事实并生成合并预览。 */
    public MergeReadiness readiness(UUID sessionId) {
        synchronized (lock(sessionId)) {
            CodingSession session = requireSession(sessionId);
            List<String> blockers = new ArrayList<>();
            if (session.status() != CodingSessionStatus.ACTIVE) {
                blockers.add("SESSION_NOT_ACTIVE");
            }
            integrations.find(sessionId).ifPresent(integration -> {
                if (integration.status() == IntegrationStatus.MERGING) {
                    blockers.add("INTEGRATION_IN_PROGRESS");
                } else if (integration.status() == IntegrationStatus.NEEDS_ATTENTION) {
                    blockers.add("INTEGRATION_NEEDS_ATTENTION");
                } else if (integration.status() == IntegrationStatus.MERGED) {
                    blockers.add("SESSION_ALREADY_MERGED");
                }
            });

            // 非 ACTIVE Session 没有合并资格，也不应继续读取不存在的 Worktree。
            if (session.status() != CodingSessionStatus.ACTIVE
                    || session.worktreePath() == null
                    || session.resolvedCommit() == null
                    || session.resolvedCommit().isBlank()) {
                return new MergeReadiness(
                        sessionId, false, "", "", "", 0, blockers
                );
            }

            List<CodingTurn> history = turns.findAll(sessionId);
            if (history.stream().anyMatch(this::active)) {
                blockers.add("TURN_IN_PROGRESS");
            }
            CodingTurn latest = history.isEmpty() ? null : history.getLast();
            if (latest == null || latest.status() != TurnStatus.COMPLETED
                    || latest.outcome() != TurnOutcome.CHANGES_VERIFIED) {
                blockers.add("NO_VERIFIED_TURN");
            }

            GitChangeSet diff = changes.collect(handle(session));
            String fingerprint = fingerprints.calculate(diff);
            if (diff.isEmpty()) {
                blockers.add("NO_CHANGES");
            }
            if (latest != null && latest.outcome() == TurnOutcome.CHANGES_VERIFIED
                    && !fingerprint.equals(latest.workspaceFingerprint())) {
                blockers.add("WORKSPACE_CHANGED_AFTER_VERIFICATION");
            }

            String sourceHead = git(session.sourceRepository(), "rev-parse", "HEAD");
            String targetBranch = git(session.sourceRepository(), "branch", "--show-current");
            if (!sourceHead.equals(session.resolvedCommit())) {
                blockers.add("SOURCE_REPOSITORY_DIVERGED");
            }
            if (targetBranch.isBlank()) {
                blockers.add("SOURCE_BRANCH_DETACHED");
            }
            if (!git(session.sourceRepository(), "status", "--porcelain",
                    "--untracked-files=all").isBlank()) {
                blockers.add("SOURCE_REPOSITORY_DIRTY");
            }
            if (gitConfig(session.sourceRepository(), "user.name").isEmpty()
                    || gitConfig(session.sourceRepository(), "user.email").isEmpty()) {
                blockers.add("GIT_IDENTITY_MISSING");
            }
            return new MergeReadiness(
                    sessionId, blockers.isEmpty(), fingerprint, sourceHead,
                    targetBranch, diff.files().size(), blockers
            );
        }
    }

    /** 二次校验所有事实后创建 Session Commit 并 Fast-forward 写回原仓库。 */
    public CodingSession merge(UUID sessionId, MergeCodingSessionRequest request) {
        Objects.requireNonNull(request, "request");
        synchronized (lock(sessionId)) {
            MergeReadiness current = readiness(sessionId);
            requireReady(current, request);
            CodingSession session = requireSession(sessionId);
            CodingSessionIntegration integration = CodingSessionIntegration.merging(
                    sessionId, current.sourceHead(), current.targetBranch(),
                    current.fingerprint(), request.commitMessage(), Instant.now()
            );
            integrations.save(integration);

            try {
                String worktreeHead = prepareSessionCommit(session, integration);
                integration = integration.withSessionCommit(worktreeHead, Instant.now());
                integrations.save(integration);

                requireWorkspaceUnchanged(session, integration.workspaceFingerprint());
                requireSourceUnchanged(session, integration);
                git(session.sourceRepository(), "merge", "--ff-only", worktreeHead);
                String mergedHead = git(session.sourceRepository(), "rev-parse", "HEAD");
                if (!mergedHead.equals(worktreeHead)) {
                    throw new IllegalStateException(
                            "原仓库合并后 HEAD 与 Session Commit 不一致。"
                    );
                }

                CodingSession merged = session.merged(Instant.now());
                sessions.save(merged);
                integrations.save(integration.merged(mergedHead, Instant.now()));
                return merged;
            } catch (RuntimeException exception) {
                recordInterruptedState(session, integration, exception);
                throw exception;
            }
        }
    }

    /** 查询指定 Session 已开始的整合事实。 */
    public Optional<CodingSessionIntegration> integration(UUID sessionId) {
        requireSession(sessionId);
        return integrations.find(sessionId);
    }

    /** 为恢复服务暴露受控的原仓库 HEAD 读取。 */
    String sourceHead(CodingSession session) {
        return git(session.sourceRepository(), "rev-parse", "HEAD");
    }

    /** 将恢复确认完成的整合收敛到 MERGED。 */
    void completeRecoveredMerge(
            CodingSession session,
            CodingSessionIntegration integration,
            String head
    ) {
        CodingSession merged = session.status() == CodingSessionStatus.MERGED
                ? session : session.merged(Instant.now());
        sessions.save(merged);
        integrations.save(integration.merged(head, Instant.now()));
    }

    /** 保存恢复服务判断出的新整合状态。 */
    void saveIntegration(CodingSessionIntegration integration) {
        integrations.save(integration);
    }

    /** 创建或复用包含精确已验证内容的 Session Commit。 */
    private String prepareSessionCommit(
            CodingSession session,
            CodingSessionIntegration integration
    ) {
        String worktreeHead = git(session.worktreePath(), "rev-parse", "HEAD");
        if (worktreeHead.equals(session.resolvedCommit())) {
            git(session.worktreePath(), "add", "-A");
            git(session.worktreePath(), "commit", "-m", integration.commitMessage());
            worktreeHead = git(session.worktreePath(), "rev-parse", "HEAD");
        }
        GitResult ancestor = runGit(
                session.worktreePath(), "merge-base", "--is-ancestor",
                session.resolvedCommit(), worktreeHead
        );
        if (ancestor.exitCode() != 0) {
            throw new CodingSessionConflictException(
                    "SESSION_COMMIT_INVALID",
                    "Session Commit 不是创建基线的后继提交，已停止合并。"
            );
        }
        return worktreeHead;
    }

    /** 在执行有副作用操作前校验预览没有过期。 */
    private void requireReady(MergeReadiness readiness, MergeCodingSessionRequest request) {
        if (!readiness.ready()) {
            throw new CodingSessionConflictException(
                    "MERGE_NOT_READY",
                    "当前状态不满足合并条件：" + String.join(", ", readiness.blockers())
            );
        }
        if (!readiness.sourceHead().equals(request.expectedSourceHead())
                || !readiness.targetBranch().equals(request.expectedTargetBranch())
                || !readiness.fingerprint().equals(request.workspaceFingerprint())) {
            throw new CodingSessionConflictException(
                    "MERGE_PREVIEW_EXPIRED", "合并预览已过期，请重新读取合并预览。"
            );
        }
    }

    /** 再次确认 Worktree 内容仍是 Maven 验证过的精确快照。 */
    private void requireWorkspaceUnchanged(CodingSession session, String expectedFingerprint) {
        GitChangeSet current = changes.collect(handle(session));
        if (!fingerprints.calculate(current).equals(expectedFingerprint)) {
            throw new CodingSessionConflictException(
                    "WORKSPACE_CHANGED_AFTER_VERIFICATION",
                    "Worktree 在验证后发生变化，必须重新运行 Coding Turn 验证。"
            );
        }
    }

    /** 在真正写回前再次检查原仓库分支、HEAD 和脏状态。 */
    private void requireSourceUnchanged(
            CodingSession session,
            CodingSessionIntegration integration
    ) {
        String branch = git(session.sourceRepository(), "branch", "--show-current");
        String head = git(session.sourceRepository(), "rev-parse", "HEAD");
        String status = git(session.sourceRepository(), "status", "--porcelain",
                "--untracked-files=all");
        if (!branch.equals(integration.targetBranch())) {
            throw new CodingSessionConflictException(
                    "SOURCE_BRANCH_CHANGED", "原仓库目标分支已经变化，已停止合并。"
            );
        }
        if (!head.equals(integration.sourceHeadBefore())) {
            throw new CodingSessionConflictException(
                    "SOURCE_REPOSITORY_DIVERGED", "原仓库 HEAD 已经变化，已停止合并。"
            );
        }
        if (!status.isBlank()) {
            throw new CodingSessionConflictException(
                    "SOURCE_REPOSITORY_DIRTY", "原仓库出现未提交修改，已停止合并。"
            );
        }
    }

    /** 依据原仓库是否已经变化记录可重试或需人工处理的终态。 */
    private void recordInterruptedState(
            CodingSession session,
            CodingSessionIntegration integration,
            RuntimeException original
    ) {
        try {
            String sourceHead = sourceHead(session);
            if (integration.sessionCommit() != null
                    && sourceHead.equals(integration.sessionCommit())) {
                completeRecoveredMerge(session, integration, sourceHead);
                return;
            }
            CodingSessionIntegration failed;
            if (sourceHead.equals(integration.sourceHeadBefore())) {
                failed = integration.interrupted(
                        "INTEGRATION_INTERRUPTED",
                        "合并在原仓库变化前中断，可以重新检查后重试。",
                        Instant.now()
                );
            } else {
                failed = integration.needsAttention(
                        "SOURCE_STATE_UNEXPECTED",
                        "原仓库处于无法自动确认的状态，请人工检查 Git 历史。",
                        Instant.now()
                );
            }
            integrations.save(failed);
        } catch (RuntimeException recoveryFailure) {
            original.addSuppressed(recoveryFailure);
        }
    }

    /** 从 Session 构造变更采集所需的通用工作区句柄。 */
    private WorkspaceHandle handle(CodingSession session) {
        return new WorkspaceHandle(session.worktreePath(), session.resolvedCommit());
    }

    /** 读取必须存在的 Session。 */
    private CodingSession requireSession(UUID id) {
        return sessions.find(id).orElseThrow(
                () -> new IllegalArgumentException("Coding Session 不存在。")
        );
    }

    /** 判断 Turn 是否仍在执行。 */
    private boolean active(CodingTurn turn) {
        return turn.status() == TurnStatus.QUEUED || turn.status() == TurnStatus.RUNNING;
    }

    /** 读取可继承的 Git 提交身份配置，缺失时返回空。 */
    private Optional<String> gitConfig(Path repository, String key) {
        GitResult result = runGit(repository, "config", "--get", key);
        if (result.exitCode() == 1) {
            return Optional.empty();
        }
        if (result.exitCode() != 0) {
            throw gitFailure(result);
        }
        return result.output().isBlank() ? Optional.empty() : Optional.of(result.output());
    }

    /** 执行必须成功的 Git 命令。 */
    private String git(Path directory, String... arguments) {
        GitResult result = runGit(directory, arguments);
        if (result.exitCode() != 0) {
            throw gitFailure(result);
        }
        return result.output();
    }

    /** 使用参数列表执行 Git，避免提交说明等动态内容进入 Shell。 */
    private GitResult runGit(Path directory, String... arguments) {
        List<String> command = new ArrayList<>(List.of("git", "-C", directory.toString()));
        command.addAll(List.of(arguments));
        Process process = null;
        ExecutorService readerExecutor = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            builder.environment().keySet().removeIf(key -> key.startsWith("GIT_"));
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            process = builder.start();
            Process startedProcess = process;
            readerExecutor = Executors.newVirtualThreadPerTaskExecutor();
            Future<String> outputFuture = readerExecutor.submit(() -> {
                try (var input = startedProcess.getInputStream()) {
                    return new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
                }
            });
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Git 命令执行超时，操作已停止。");
            }
            String output;
            try {
                output = outputFuture.get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException exception) {
                process.destroyForcibly();
                throw new IllegalStateException("Git 命令输出读取超时，操作已停止。", exception);
            } catch (java.util.concurrent.ExecutionException exception) {
                throw new IllegalStateException("Git 命令输出读取失败。", exception.getCause());
            }
            return new GitResult(process.exitValue(), output, command);
        } catch (IOException exception) {
            throw new IllegalStateException("无法执行 Git：" + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            throw new IllegalStateException("Git 操作被中断。", exception);
        } finally {
            if (readerExecutor != null) {
                readerExecutor.shutdownNow();
            }
        }
    }

    /** 创建 Git 失败异常。 */
    private IllegalStateException gitFailure(GitResult result) {
        return new IllegalStateException("Git 操作失败：" + result.output());
    }

    /** 返回指定 Session 的整合互斥锁。 */
    private Object lock(UUID sessionId) {
        return sessionLocks.computeIfAbsent(sessionId, ignored -> new Object());
    }

    /** Git 子进程的退出事实。 */
    private record GitResult(int exitCode, String output, List<String> command) {
        private GitResult {
            command = List.copyOf(command);
        }
    }
}
