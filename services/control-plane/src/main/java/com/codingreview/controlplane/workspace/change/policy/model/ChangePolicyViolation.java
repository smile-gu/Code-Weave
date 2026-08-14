package com.codingreview.controlplane.workspace.change.policy.model;

import com.codingreview.controlplane.workspace.change.model.GitChangeType;

import java.util.Objects;

/** 单个仓库相对路径的策略违规详情。 */
public record ChangePolicyViolation(
        String path,
        GitChangeType changeType,
        ChangePolicyViolationType violationType,
        String matchedRule,
        String message
) {
    public ChangePolicyViolation {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        Objects.requireNonNull(changeType, "changeType");
        Objects.requireNonNull(violationType, "violationType");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        if (violationType == ChangePolicyViolationType.DENIED_PATH
                && (matchedRule == null || matchedRule.isBlank())) {
            throw new IllegalArgumentException("matchedRule is required for denied paths");
        }
    }
}
