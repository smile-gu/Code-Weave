package com.codingreview.controlplane.api.system.dto;

import com.codingreview.controlplane.runtime.readiness.model.RuntimeCheck;
import com.codingreview.controlplane.runtime.readiness.model.RuntimeCheckStatus;
import com.codingreview.controlplane.runtime.readiness.model.RuntimeComponent;

/** 对外暴露单项环境检查的稳定字段。 */
public record RuntimeCheckResponse(
        RuntimeComponent component,
        RuntimeCheckStatus status,
        boolean required,
        String version,
        String executable,
        String message,
        String remediation
) {
    /** 将内部检查模型转换为 API 响应。 */
    public static RuntimeCheckResponse from(RuntimeCheck check) {
        return new RuntimeCheckResponse(
                check.component(),
                check.status(),
                check.required(),
                check.version(),
                check.executable(),
                check.message(),
                check.remediation()
        );
    }
}
