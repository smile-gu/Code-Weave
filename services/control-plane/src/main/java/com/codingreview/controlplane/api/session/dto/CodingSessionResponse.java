package com.codingreview.controlplane.api.session.dto;

import com.codingreview.controlplane.session.model.CodingSession;
import com.codingreview.controlplane.session.model.CodingSessionStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Coding Session 详情和历史列表共用的稳定响应。 */
public record CodingSessionResponse(
        UUID sessionId,
        String title,
        CodingSessionStatus status,
        String sourceRepository,
        String worktreePath,
        String baseRef,
        String resolvedCommit,
        String activeReviewBaselineCommit,
        boolean sourceRepositoryDirty,
        List<String> allowedPaths,
        List<String> deniedPaths,
        String claudeSessionId,
        Instant createdAt,
        Instant updatedAt,
        String failureCode,
        String failureMessage
) {
    /** 将领域 Session 映射为显式 API DTO。 */
    public static CodingSessionResponse from(CodingSession session) {
        return new CodingSessionResponse(
                session.sessionId(), session.title(), session.status(),
                session.sourceRepository().toString(),
                session.worktreePath() == null ? null : session.worktreePath().toString(),
                session.baseRef(), session.resolvedCommit(),
                session.activeReviewBaselineCommit(), session.sourceRepositoryDirty(),
                session.changePolicy().allowedPaths(), session.changePolicy().deniedPaths(),
                session.claudeSessionId(),
                session.createdAt(), session.updatedAt(),
                session.failureCode(), session.failureMessage()
        );
    }
}
