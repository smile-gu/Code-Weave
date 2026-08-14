package com.codingreview.controlplane.verification.maven.runtime;

import com.codingreview.controlplane.verification.maven.model.MavenLogArtifact;

import java.time.Instant;
import java.util.Objects;

/** Maven 子进程退出和两路原始日志采集的结果。 */
public record MavenProcessResult(
        MavenProcessStatus status,
        Integer exitCode,
        Instant startedAt,
        Instant endedAt,
        MavenLogArtifact stdoutLog,
        MavenLogArtifact stderrLog,
        String diagnostic
) {
    public MavenProcessResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(endedAt, "endedAt");
        if (endedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("endedAt must not be before startedAt");
        }
    }
}
