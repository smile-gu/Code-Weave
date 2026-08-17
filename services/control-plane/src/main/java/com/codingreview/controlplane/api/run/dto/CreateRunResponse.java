package com.codingreview.controlplane.api.run.dto;

import com.codingreview.controlplane.application.run.RunExecutionStatus;

import java.util.UUID;

/** 接受异步 Run 后返回的查询入口。 */
public record CreateRunResponse(
        UUID runId,
        RunExecutionStatus status,
        String statusUrl,
        String eventsUrl
) {
    public static CreateRunResponse queued(UUID runId) {
        String baseUrl = "/api/v1/m0/runs/" + runId;
        return new CreateRunResponse(
                runId,
                RunExecutionStatus.QUEUED,
                baseUrl,
                baseUrl + "/events"
        );
    }
}
