package com.codingreview.controlplane.workspace.change.policy.model;

import java.util.List;

/** 一次代码变更的路径合规结果。 */
public record ChangePolicyResult(
        List<ChangePolicyViolation> violations
) {
    public ChangePolicyResult {
        violations = List.copyOf(violations);
    }

    /** 判断本次代码变更是否满足全部路径规则。 */
    public boolean compliant() {
        return violations.isEmpty();
    }
}
