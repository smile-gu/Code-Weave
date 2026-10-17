package com.codingreview.controlplane.review.snapshot;

import java.time.Duration;

public record SnapshotLimits(
        int files,
        long fileBytes,
        long totalBytes,
        int manifestBytes,
        int diffBytes,
        Duration timeout) {
    public SnapshotLimits {
        if (files <= 0
                || fileBytes <= 0
                || totalBytes <= 0
                || manifestBytes <= 0
                || diffBytes <= 0
                || timeout == null
                || timeout.isNegative()
                || timeout.isZero())
            throw new IllegalArgumentException("Positive Snapshot limits required");
    }

    public static SnapshotLimits defaults() {
        return new SnapshotLimits(
                20_000,
                64L * 1024 * 1024,
                1024L * 1024 * 1024,
                16 * 1024 * 1024,
                2 * 1024 * 1024,
                Duration.ofSeconds(120));
    }
}
