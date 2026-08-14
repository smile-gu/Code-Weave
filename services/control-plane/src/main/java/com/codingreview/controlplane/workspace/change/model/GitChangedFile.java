package com.codingreview.controlplane.workspace.change.model;

import java.util.Objects;

/** 一次任务中发生变化的文件元数据。 */
public record GitChangedFile(
        String path,
        String oldPath,
        GitChangeType changeType,
        boolean binary
) {
    public GitChangedFile {
        path = normalizeRelativePath(path, "path");
        Objects.requireNonNull(changeType, "changeType");
        if (oldPath != null) {
            oldPath = normalizeRelativePath(oldPath, "oldPath");
        }
        if (changeType == GitChangeType.RENAMED && oldPath == null) {
            throw new IllegalArgumentException("oldPath is required for renamed files");
        }
    }

    private static String normalizeRelativePath(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:/.*")) {
            throw new IllegalArgumentException(field + " must be relative");
        }
        return normalized;
    }
}
