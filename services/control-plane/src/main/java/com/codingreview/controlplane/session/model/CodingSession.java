package com.codingreview.controlplane.session.model;

import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicy;
import com.codingreview.controlplane.workspace.git.model.GitRepositorySnapshot;
import com.codingreview.controlplane.workspace.worktree.model.WorktreeHandle;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 表示一个跨多轮 Coding Turn 持续存在的开发会话。 */
public record CodingSession(
        UUID sessionId,
        String title,
        CodingSessionStatus status,
        Path sourceRepository,
        Path worktreePath,
        String baseRef,
        String resolvedCommit,
        String activeReviewBaselineCommit,
        boolean sourceRepositoryDirty,
        ChangePolicy changePolicy,
        String claudeSessionId,
        Instant createdAt,
        Instant updatedAt,
        String failureCode,
        String failureMessage
) {

    /** 兼容尚未包含活动审查基线字段、但已绑定 Claude 上下文的旧调用方。 */
    public CodingSession(
            UUID sessionId, String title, CodingSessionStatus status,
            Path sourceRepository, Path worktreePath, String baseRef, String resolvedCommit,
            boolean sourceRepositoryDirty, ChangePolicy changePolicy, String claudeSessionId,
            Instant createdAt, Instant updatedAt, String failureCode, String failureMessage
    ) {
        this(sessionId, title, status, sourceRepository, worktreePath, baseRef, resolvedCommit,
                resolvedCommit, sourceRepositoryDirty, changePolicy, claudeSessionId,
                createdAt, updatedAt, failureCode, failureMessage);
    }

    /** 兼容 M5 快照和调用方创建的未绑定 Claude 上下文的 Session。 */
    public CodingSession(
            UUID sessionId, String title, CodingSessionStatus status,
            Path sourceRepository, Path worktreePath, String baseRef, String resolvedCommit,
            boolean sourceRepositoryDirty, ChangePolicy changePolicy,
            Instant createdAt, Instant updatedAt, String failureCode, String failureMessage
    ) {
        this(sessionId, title, status, sourceRepository, worktreePath, baseRef, resolvedCommit,
                resolvedCommit, sourceRepositoryDirty, changePolicy, null, createdAt, updatedAt,
                failureCode, failureMessage);
    }

    /** 校验并规范化 Session 的完整持久状态。 */
    public CodingSession {
        Objects.requireNonNull(sessionId, "sessionId");
        title = requireText(title, "title");
        Objects.requireNonNull(status, "status");
        sourceRepository = requireAbsolute(sourceRepository, "sourceRepository");
        if (worktreePath != null) {
            worktreePath = requireAbsolute(worktreePath, "worktreePath");
        }
        baseRef = requireText(baseRef, "baseRef");
        Objects.requireNonNull(changePolicy, "changePolicy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
        if (status == CodingSessionStatus.ACTIVE
                && (worktreePath == null || resolvedCommit == null || resolvedCommit.isBlank()
                || activeReviewBaselineCommit == null || activeReviewBaselineCommit.isBlank())) {
            throw new IllegalArgumentException("active session requires workspace and baseline commits");
        }
        if (status == CodingSessionStatus.FAILED
                && (failureCode == null || failureCode.isBlank()
                || failureMessage == null || failureMessage.isBlank())) {
            throw new IllegalArgumentException("failed session requires failure details");
        }
    }

    /** 创建尚未完成 Worktree 初始化的 Session。 */
    public static CodingSession initializing(
            UUID sessionId,
            String title,
            Path sourceRepository,
            String baseRef,
            ChangePolicy changePolicy,
            Instant now
    ) {
        return new CodingSession(
                sessionId, title, CodingSessionStatus.INITIALIZING,
                sourceRepository, null, baseRef, null, null, false,
                changePolicy, null, now, now, null, null
        );
    }

    /** 使用创建完成的 Worktree 激活 Session。 */
    public CodingSession activate(WorktreeHandle handle, Instant now) {
        Objects.requireNonNull(handle, "handle");
        if (status != CodingSessionStatus.INITIALIZING) {
            throw new IllegalStateException("only initializing session can become active");
        }
        if (!handle.workspaceId().equals(sessionId.toString())) {
            throw new IllegalArgumentException("worktree does not belong to session");
        }
        return new CodingSession(
                sessionId, title, CodingSessionStatus.ACTIVE,
                handle.sourceRepository(), handle.worktreePath(), handle.baseRef(),
                handle.resolvedCommit(), handle.resolvedCommit(),
                handle.sourceRepositoryDirty(), changePolicy,
                claudeSessionId, createdAt, now, null, null
        );
    }

    /** 绑定用户选择的真实 Git 工作区，不创建额外目录。 */
    public CodingSession activateRealRepository(GitRepositorySnapshot snapshot, Instant now) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (status != CodingSessionStatus.INITIALIZING) {
            throw new IllegalStateException("only initializing session can become active");
        }
        if (!sourceRepository.equals(snapshot.repositoryRoot())) {
            throw new IllegalArgumentException("repository snapshot does not belong to session");
        }
        return new CodingSession(
                sessionId, title, CodingSessionStatus.ACTIVE,
                snapshot.repositoryRoot(), snapshot.repositoryRoot(), snapshot.branch(),
                snapshot.headCommit(), snapshot.headCommit(), snapshot.dirty(), changePolicy,
                claudeSessionId, createdAt, now, null, null
        );
    }

    /** 判断当前 Session 是否直接使用用户真实 Git 工作区。 */
    public boolean usesRealRepository() {
        return status != CodingSessionStatus.LEGACY_READ_ONLY
                && worktreePath != null
                && worktreePath.equals(sourceRepository);
    }

    /** 记录 Claude Code 在首轮返回的原生会话标识，供后续 Turn 恢复上下文。 */
    public CodingSession bindClaudeSession(String nativeSessionId, Instant now) {
        if (status != CodingSessionStatus.ACTIVE) {
            throw new IllegalStateException("only active session can bind Claude session");
        }
        return new CodingSession(
                sessionId, title, status, sourceRepository, worktreePath, baseRef, resolvedCommit,
                activeReviewBaselineCommit,
                sourceRepositoryDirty, changePolicy, requireText(nativeSessionId, "claudeSessionId"),
                createdAt, now, failureCode, failureMessage
        );
    }

    /** 将用户已确认的新提交设为下一审查周期的活动基线。 */
    public CodingSession advanceReviewBaseline(String commit, Instant now) {
        if (status != CodingSessionStatus.ACTIVE || !usesRealRepository()) {
            throw new IllegalStateException("only active direct-workspace session can advance baseline");
        }
        return new CodingSession(
                sessionId, title, status, sourceRepository, worktreePath, baseRef,
                resolvedCommit, requireText(commit, "activeReviewBaselineCommit"),
                sourceRepositoryDirty, changePolicy, claudeSessionId,
                createdAt, now, failureCode, failureMessage
        );
    }

    /** 在 Session 业务内容更新后刷新最近活动时间。 */
    public CodingSession touch(Instant now) {
        if (now.isBefore(updatedAt)) {
            throw new IllegalArgumentException("now must not be before updatedAt");
        }
        return new CodingSession(sessionId, title, status, sourceRepository, worktreePath,
                baseRef, resolvedCommit, activeReviewBaselineCommit,
                sourceRepositoryDirty, changePolicy,
                claudeSessionId, createdAt, now, failureCode, failureMessage);
    }

    /** 将未关闭的 Session 转换为带稳定原因的失败状态。 */
    public CodingSession fail(String code, String message, Instant now) {
        if (status == CodingSessionStatus.CLOSED || status == CodingSessionStatus.MERGED) {
            throw new IllegalStateException("terminal session cannot become failed");
        }
        return new CodingSession(
                sessionId, title, CodingSessionStatus.FAILED,
                sourceRepository, worktreePath, baseRef, resolvedCommit,
                activeReviewBaselineCommit,
                sourceRepositoryDirty, changePolicy, claudeSessionId, createdAt, now,
                requireText(code, "failureCode"), requireText(message, "failureMessage")
        );
    }

    /** 校验并规范化必填文本。 */
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    /** 校验并规范化绝对路径。 */
    private static Path requireAbsolute(Path value, String field) {
        Objects.requireNonNull(value, field);
        if (!value.isAbsolute()) {
            throw new IllegalArgumentException(field + " must be absolute");
        }
        return value.normalize();
    }
}
