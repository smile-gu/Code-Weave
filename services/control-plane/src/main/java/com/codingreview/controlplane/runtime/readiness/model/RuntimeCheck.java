package com.codingreview.controlplane.runtime.readiness.model;

import java.util.Objects;

/** 描述一个本机组件的可用性结论和用户处理建议。 */
public record RuntimeCheck(
        RuntimeComponent component,
        RuntimeCheckStatus status,
        boolean required,
        String version,
        String executable,
        String message,
        String remediation
) {
    /** 校验检查项必须具备稳定状态和用户可读消息。 */
    public RuntimeCheck {
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(status, "status");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }

    /** 判断该检查是否会阻止创建 Coding Run。 */
    public boolean blocking() {
        return required && status == RuntimeCheckStatus.FAILED;
    }
}
