package com.codingreview.controlplane.review;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties("code-weave.review")
public record ReviewProperties(
        Path snapshotRoot,
        Path executionRoot,
        @DefaultValue("http://127.0.0.1:8091") URI harnessBaseUrl,
        String internalBearerToken,
        @DefaultValue("32") int queueCapacity,
        @DefaultValue("5s") Duration connectionTimeout,
        @DefaultValue("10s") Duration requestTimeout,
        @DefaultValue("15s") Duration sseIdleTimeout,
        @DefaultValue("5s") Duration reconcileInterval,
        @DefaultValue("20000") int maxFiles,
        @DefaultValue("67108864") long maxFileBytes,
        @DefaultValue("1073741824") long maxTotalBytes,
        @DefaultValue("16777216") int maxManifestBytes,
        @DefaultValue("2097152") int maxDiffBytes,
        @DefaultValue("120s") Duration captureTimeout) {
    public ReviewProperties {
        if (queueCapacity <= 0
                || queueCapacity > 10000
                || reconcileInterval == null
                || reconcileInterval.isNegative()
                || reconcileInterval.isZero())
            throw new IllegalArgumentException("Bounded Review scheduling required");
    }

    public Path snapshots(Path runtime) {
        return absolute(snapshotRoot == null ? runtime.resolve("review-snapshots") : snapshotRoot);
    }

    public Path executions(Path runtime) {
        return absolute(
                executionRoot == null ? runtime.resolve("review-workspaces") : executionRoot);
    }

    private static Path absolute(Path path) {
        if (!path.isAbsolute()) throw new IllegalArgumentException("Absolute Review root required");
        return path.normalize();
    }
}
