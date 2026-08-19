package com.codingreview.controlplane.api.session.dto;

import com.codingreview.controlplane.session.integration.model.CodingSessionIntegration;
import com.codingreview.controlplane.session.integration.model.IntegrationStatus;

import java.time.Instant;
import java.util.UUID;

/** 面向前端的 Session 合并持久事实。 */
public record CodingSessionIntegrationResponse(
        UUID sessionId,
        IntegrationStatus status,
        String sourceHeadBefore,
        String targetBranch,
        String workspaceFingerprint,
        String sessionCommit,
        String mergedHead,
        Instant startedAt,
        Instant updatedAt,
        String failureCode,
        String failureMessage
) {
    /** 将内部整合状态映射为不包含提交说明的公开响应。 */
    public static CodingSessionIntegrationResponse from(
            CodingSessionIntegration integration
    ) {
        return new CodingSessionIntegrationResponse(
                integration.sessionId(), integration.status(),
                integration.sourceHeadBefore(), integration.targetBranch(),
                integration.workspaceFingerprint(), integration.sessionCommit(),
                integration.mergedHead(), integration.startedAt(), integration.updatedAt(),
                integration.failureCode(), integration.failureMessage()
        );
    }
}
