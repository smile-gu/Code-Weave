package com.codingreview.controlplane.api.session.dto;

import com.codingreview.controlplane.application.session.MergeReadiness;
import java.util.List;
import java.util.UUID;

/** 合并预览接口响应。 */
public record MergeReadinessResponse(UUID sessionId, boolean ready, String workspaceFingerprint,
                                     String sourceHead, String targetBranch, int changedFiles, List<String> blockers) {
    /** 映射合并就绪检查结果。 */
    public static MergeReadinessResponse from(MergeReadiness readiness) { return new MergeReadinessResponse(readiness.sessionId(), readiness.ready(), readiness.fingerprint(), readiness.sourceHead(), readiness.targetBranch(), readiness.changedFiles(), readiness.blockers()); }
}
