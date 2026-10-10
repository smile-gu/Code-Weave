package com.codingreview.controlplane.review.snapshot;

import com.codingreview.controlplane.workspace.change.model.GitChangedFile;

import java.util.*;

public record ScopePreview(
        UUID sessionId,
        String activeBaselineCommit,
        String headCommit,
        String branch,
        String workspaceFingerprint,
        List<GitChangedFile> changedFiles,
        List<String> changedPaths) {
    public ScopePreview {
        changedFiles = List.copyOf(changedFiles);
        changedPaths = List.copyOf(changedPaths);
    }
}
