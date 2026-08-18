package com.codingreview.controlplane.api.system.dto;

import com.codingreview.controlplane.runtime.readiness.model.RuntimeReadiness;
import com.codingreview.controlplane.runtime.readiness.model.RuntimeReadinessStatus;

import java.time.Instant;
import java.util.List;

/** 对外暴露当前本机环境的整体就绪状态。 */
public record RuntimeReadinessResponse(
        RuntimeReadinessStatus status,
        Instant checkedAt,
        List<RuntimeCheckResponse> checks
) {
    /** 将内部就绪模型转换为 API 响应。 */
    public static RuntimeReadinessResponse from(RuntimeReadiness readiness) {
        return new RuntimeReadinessResponse(
                readiness.status(),
                readiness.checkedAt(),
                readiness.checks().stream().map(RuntimeCheckResponse::from).toList()
        );
    }
}
