package com.codingreview.controlplane.verification.maven.runtime;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Maven 子进程已经冻结的启动与资源限制。 */
public record MavenProcessSpec(
        List<String> command,
        Path workingDirectory,
        Path artifactDirectory,
        Path runtimeRoot,
        Duration timeout,
        Duration cancellationGracePeriod,
        long maxCombinedLogBytes
) {
    public MavenProcessSpec {
        command = List.copyOf(command);
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(artifactDirectory, "artifactDirectory");
        Objects.requireNonNull(runtimeRoot, "runtimeRoot");
        if (!workingDirectory.isAbsolute() || !artifactDirectory.isAbsolute() || !runtimeRoot.isAbsolute()) {
            throw new IllegalArgumentException("process paths must be absolute");
        }
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(cancellationGracePeriod, "cancellationGracePeriod");
        if (timeout.isZero() || timeout.isNegative() || cancellationGracePeriod.isNegative()) {
            throw new IllegalArgumentException("process durations are invalid");
        }
        if (maxCombinedLogBytes < 1) {
            throw new IllegalArgumentException("maxCombinedLogBytes must be positive");
        }
    }
}
