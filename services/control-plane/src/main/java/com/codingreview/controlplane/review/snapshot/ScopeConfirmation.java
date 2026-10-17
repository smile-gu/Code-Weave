package com.codingreview.controlplane.review.snapshot;

import java.util.List;

public record ScopeConfirmation(
        String expectedActiveBaselineCommit,
        String expectedHeadCommit,
        String expectedBranch,
        String expectedWorkspaceFingerprint,
        List<String> targetPaths) {
    public ScopeConfirmation {
        targetPaths = targetPaths == null ? List.of() : List.copyOf(targetPaths);
    }
}
