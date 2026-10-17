package com.codingreview.controlplane.review.snapshot;

import java.util.UUID;

public record SnapshotPublication(
        int schemaVersion,
        UUID snapshotId,
        UUID sessionId,
        String manifestSha256,
        String createdAt) {}
