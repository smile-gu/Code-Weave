package com.codingreview.controlplane.adapter.claude.runtime;

/** Claude 运行时事件违反启动安全契约后的结构化结论。 */
record ClaudeRuntimePolicyViolation(
        ClaudeRunStatus status,
        String diagnostic
) {
}
