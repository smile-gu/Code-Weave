package com.codingreview.controlplane.verification.maven.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Maven 进程、日志证据和测试报告共同形成的验证结论。 */
public record MavenVerificationResult(
        MavenVerificationStatus status,
        Integer exitCode,
        List<String> command,
        Instant startedAt,
        Instant endedAt,
        MavenLogArtifact stdoutLog,
        MavenLogArtifact stderrLog,
        MavenTestSummary testSummary,
        String userMessage,
        String diagnostic
) {
    public MavenVerificationResult {
        Objects.requireNonNull(status, "status");
        command = List.copyOf(command);
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(endedAt, "endedAt");
        if (endedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("endedAt must not be before startedAt");
        }
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("userMessage must not be blank");
        }
    }

    /** 判断 Maven 验证是否完整通过。 */
    public boolean passed() {
        return status == MavenVerificationStatus.PASSED;
    }
}
