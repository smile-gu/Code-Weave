package com.codingreview.controlplane.session.integration.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 保存一次 Session 写回原仓库的关键事实和恢复依据。 */
public record CodingSessionIntegration(
        UUID sessionId,
        IntegrationStatus status,
        String sourceHeadBefore,
        String targetBranch,
        String workspaceFingerprint,
        String commitMessage,
        String sessionCommit,
        String mergedHead,
        Instant startedAt,
        Instant updatedAt,
        String failureCode,
        String failureMessage
) {
    public CodingSessionIntegration {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(status, "status");
        requireText(sourceHeadBefore, "sourceHeadBefore");
        requireText(targetBranch, "targetBranch");
        requireText(workspaceFingerprint, "workspaceFingerprint");
        requireText(commitMessage, "commitMessage");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("updatedAt must not be before startedAt");
        }
        if (status == IntegrationStatus.MERGED
                && (sessionCommit == null || sessionCommit.isBlank()
                || mergedHead == null || mergedHead.isBlank())) {
            throw new IllegalArgumentException("merged integration requires commit facts");
        }
        if ((status == IntegrationStatus.INTERRUPTED
                || status == IntegrationStatus.NEEDS_ATTENTION)
                && (failureCode == null || failureCode.isBlank()
                || failureMessage == null || failureMessage.isBlank())) {
            throw new IllegalArgumentException("failed integration state requires failure details");
        }
    }

    /** 创建写回开始前的持久恢复点。 */
    public static CodingSessionIntegration merging(
            UUID sessionId,
            String sourceHead,
            String targetBranch,
            String fingerprint,
            String commitMessage,
            Instant now
    ) {
        return new CodingSessionIntegration(
                sessionId, IntegrationStatus.MERGING, sourceHead, targetBranch,
                fingerprint, commitMessage, null, null,
                now, now, null, null
        );
    }

    /** 记录 Worktree 已创建的正式 Session Commit。 */
    public CodingSessionIntegration withSessionCommit(String commit, Instant now) {
        return new CodingSessionIntegration(
                sessionId, IntegrationStatus.MERGING, sourceHeadBefore, targetBranch,
                workspaceFingerprint, commitMessage, requireText(commit, "sessionCommit"),
                null, startedAt, now, null, null
        );
    }

    /** 记录原仓库已经 Fast-forward 到 Session Commit。 */
    public CodingSessionIntegration merged(String head, Instant now) {
        String commit = requireText(sessionCommit, "sessionCommit");
        return new CodingSessionIntegration(
                sessionId, IntegrationStatus.MERGED, sourceHeadBefore, targetBranch,
                workspaceFingerprint, commitMessage, commit,
                requireText(head, "mergedHead"), startedAt, now, null, null
        );
    }

    /** 标记操作在原仓库尚未变化时中断，允许用户安全重试。 */
    public CodingSessionIntegration interrupted(String code, String message, Instant now) {
        return failed(IntegrationStatus.INTERRUPTED, code, message, now);
    }

    /** 标记原仓库出现无法自动解释的变化，要求人工检查。 */
    public CodingSessionIntegration needsAttention(String code, String message, Instant now) {
        return failed(IntegrationStatus.NEEDS_ATTENTION, code, message, now);
    }

    /** 创建带稳定失败原因的整合状态。 */
    private CodingSessionIntegration failed(
            IntegrationStatus target,
            String code,
            String message,
            Instant now
    ) {
        return new CodingSessionIntegration(
                sessionId, target, sourceHeadBefore, targetBranch,
                workspaceFingerprint, commitMessage, sessionCommit,
                mergedHead, startedAt, now,
                requireText(code, "failureCode"), requireText(message, "failureMessage")
        );
    }

    /** 校验稳定文档中的必填文本。 */
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
