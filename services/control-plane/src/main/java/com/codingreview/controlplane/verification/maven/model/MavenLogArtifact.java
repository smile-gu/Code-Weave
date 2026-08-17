package com.codingreview.controlplane.verification.maven.model;

/** 已可靠落盘的单路 Maven 原始日志元数据。 */
public record MavenLogArtifact(
        String relativePath,
        long bytes,
        long lines,
        String sha256
) {
    public MavenLogArtifact {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("relativePath must not be blank");
        }
        if (bytes < 0 || lines < 0) {
            throw new IllegalArgumentException("log counters must not be negative");
        }
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be a lowercase SHA-256 value");
        }
    }
}
