package com.codingreview.controlplane.api.session.dto;

import com.codingreview.controlplane.application.session.ReviewBaselineReadiness;

import java.util.List;
import java.util.UUID;

/** 前端确认模块正式提交与活动基线的预览信息。 */
public record ReviewBaselineReadinessResponse(
        UUID sessionId,
        boolean ready,
        String activeBaselineCommit,
        String currentHead,
        String branch,
        int changedFiles,
        String workspaceFingerprint,
        List<String> blockers
) {
    /** 将应用层预览映射为稳定 HTTP 响应。 */
    public static ReviewBaselineReadinessResponse from(ReviewBaselineReadiness readiness) {
        return new ReviewBaselineReadinessResponse(
                readiness.sessionId(), readiness.ready(),
                readiness.activeBaselineCommit(), readiness.currentHead(),
                readiness.branch(), readiness.changedFiles(),
                readiness.workspaceFingerprint(), readiness.blockers()
        );
    }
}
