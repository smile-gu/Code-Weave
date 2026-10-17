package com.codingreview.controlplane.api.session.dto;

import jakarta.validation.constraints.NotBlank;

/** 提交基于最新预览的模块完成与活动基线推进确认。 */
public record AdvanceReviewBaselineRequest(
        @NotBlank(message = "expectedActiveBaselineCommit 不能为空")
        String expectedActiveBaselineCommit,
        @NotBlank(message = "expectedHead 不能为空")
        String expectedHead,
        @NotBlank(message = "workspaceFingerprint 不能为空")
        String workspaceFingerprint
) {
}
