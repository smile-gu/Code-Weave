package com.codingreview.controlplane.legacy.worktreerun.api.dto;

import com.codingreview.controlplane.legacy.worktreerun.application.RunExecutionStatus;

import java.util.UUID;

/** 接受异步 Run 后返回的查询入口。 */
public record CreateRunResponse(
        UUID runId,
        RunExecutionStatus status,
        String statusUrl,
        String eventsUrl
) {
    /** 创建表示任务已排队的提交响应。 */
    public static CreateRunResponse queued(UUID runId) {
        String baseUrl = "/api/v1/legacy/worktree-runs/" + runId;
        return new CreateRunResponse(
                runId,
                RunExecutionStatus.QUEUED,
                baseUrl,
                baseUrl + "/events"
        );
    }
}
