package com.codingreview.controlplane.runtime.readiness.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 汇总一次实时环境检查的整体结论。 */
public record RuntimeReadiness(
        RuntimeReadinessStatus status,
        Instant checkedAt,
        List<RuntimeCheck> checks
) {
    /** 校验整体状态必须与阻断检查项保持一致。 */
    public RuntimeReadiness {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(checkedAt, "checkedAt");
        checks = List.copyOf(checks);
        boolean hasBlockingFailure = checks.stream().anyMatch(RuntimeCheck::blocking);
        if ((status == RuntimeReadinessStatus.READY) == hasBlockingFailure) {
            throw new IllegalArgumentException("readiness status does not match checks");
        }
    }

    /** 根据检查项自动计算整体就绪状态。 */
    public static RuntimeReadiness from(Instant checkedAt, List<RuntimeCheck> checks) {
        List<RuntimeCheck> copy = List.copyOf(checks);
        RuntimeReadinessStatus status = copy.stream().anyMatch(RuntimeCheck::blocking)
                ? RuntimeReadinessStatus.NOT_READY
                : RuntimeReadinessStatus.READY;
        return new RuntimeReadiness(status, checkedAt, copy);
    }

    /** 判断当前环境是否允许创建 Coding Run。 */
    public boolean ready() {
        return status == RuntimeReadinessStatus.READY;
    }
}
