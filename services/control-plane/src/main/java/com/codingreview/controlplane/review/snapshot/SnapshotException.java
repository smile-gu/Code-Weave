package com.codingreview.controlplane.review.snapshot;

/** 安全的 Snapshot 失败分类；资源未知时调用方必须保留启动预留。 */
public final class SnapshotException extends RuntimeException {
    private final String code;
    private final boolean resourcesSafe;

    public SnapshotException(String code) {
        this(code, true);
    }

    public SnapshotException(String code, boolean resourcesSafe) {
        super("Review Snapshot 未能安全完成：" + code);
        this.code = code;
        this.resourcesSafe = resourcesSafe;
    }

    public String code() {
        return code;
    }

    public boolean resourcesSafe() {
        return resourcesSafe;
    }
}
