package com.codingreview.controlplane.application.session;

/** 用户基于最新预览确认模块正式提交并推进活动审查基线。 */
public record AdvanceReviewBaselineRequest(
        String expectedActiveBaselineCommit,
        String expectedHead,
        String workspaceFingerprint
) {
    public AdvanceReviewBaselineRequest {
        expectedActiveBaselineCommit = requireText(
                expectedActiveBaselineCommit, "expectedActiveBaselineCommit"
        );
        expectedHead = requireText(expectedHead, "expectedHead");
        workspaceFingerprint = requireText(workspaceFingerprint, "workspaceFingerprint");
    }

    /** 校验并规范化用于并发确认的文本字段。 */
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
