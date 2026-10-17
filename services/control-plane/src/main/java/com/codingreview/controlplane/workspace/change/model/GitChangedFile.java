package com.codingreview.controlplane.workspace.change.model;

import java.util.Objects;

/** 一次任务中发生变化的文件元数据。 */
public record GitChangedFile(
        String path,
        String oldPath,
        GitChangeType changeType,
        boolean binary,
        String gitMode
) {
    /** 为旧调用方补充普通文件或删除文件的默认 Git mode。 */
    public GitChangedFile(String path, String oldPath, GitChangeType changeType, boolean binary) {
        this(path, oldPath, changeType, binary,
                changeType == GitChangeType.DELETED ? "000000" : "100644");
    }

    /** 校验变更路径和 Git mode，保证采集结果可以参与稳定指纹计算。 */
    public GitChangedFile {
        path = normalizeRelativePath(path, "path");
        Objects.requireNonNull(changeType, "changeType");
        if (oldPath != null) {
            oldPath = normalizeRelativePath(oldPath, "oldPath");
        }
        if (changeType == GitChangeType.RENAMED && oldPath == null) {
            throw new IllegalArgumentException("oldPath is required for renamed files");
        }
        if (gitMode == null || !gitMode.matches("[0-7]{6}")) {
            throw new IllegalArgumentException("gitMode must be a six-digit Git mode");
        }
        if (changeType == GitChangeType.DELETED && !"000000".equals(gitMode)) {
            throw new IllegalArgumentException("deleted files must use Git mode 000000");
        }
        if (changeType != GitChangeType.DELETED && "000000".equals(gitMode)) {
            throw new IllegalArgumentException("non-deleted files must have a final Git mode");
        }
    }

    /** 校验并规范化仓库相对路径。 */
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
