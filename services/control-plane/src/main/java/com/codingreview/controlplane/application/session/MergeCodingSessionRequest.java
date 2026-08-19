package com.codingreview.controlplane.application.session;

/** 用户确认合并时携带的并发校验条件和 Git 提交信息。 */
public record MergeCodingSessionRequest(
        String commitMessage, String expectedSourceHead, String expectedTargetBranch, String workspaceFingerprint
) {
    /** 校验合并请求中的所有事实条件。 */
    public MergeCodingSessionRequest {
        require(commitMessage, "commitMessage"); require(expectedSourceHead, "expectedSourceHead");
        require(expectedTargetBranch, "expectedTargetBranch"); require(workspaceFingerprint, "workspaceFingerprint");
    }
    /** 检查文本不能为空。 */
    private static void require(String value, String field) { if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " 不能为空"); }
}
