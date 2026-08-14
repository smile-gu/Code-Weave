package com.codingreview.controlplane.workspace.change.policy.model;

import java.util.List;

/** 一次代码变更的路径合规结果。 */
public record ChangePolicyResult(
        List<ChangePolicyViolation> violations
) {
    public ChangePolicyResult {
        violations = List.copyOf(violations);
    }

    public boolean compliant() {
        return violations.isEmpty();
    }
}
