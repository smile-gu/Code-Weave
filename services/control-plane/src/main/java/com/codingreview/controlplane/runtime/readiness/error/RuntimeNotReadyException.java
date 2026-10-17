package com.codingreview.controlplane.runtime.readiness.error;

import com.codingreview.controlplane.runtime.readiness.model.RuntimeReadiness;

import java.util.Objects;

/** 表示当前本机环境不能安全创建 Coding Run。 */
public final class RuntimeNotReadyException extends RuntimeException {

    private final RuntimeReadiness readiness;

    /** 保存触发 Run 门禁的完整环境检查结果。 */
    public RuntimeNotReadyException(RuntimeReadiness readiness) {
        super("当前本机运行环境尚未就绪，请根据环境检查结果完成配置后重试。");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
    }

    /** 返回触发门禁的完整环境检查结果。 */
    public RuntimeReadiness readiness() {
        return readiness;
    }
}
