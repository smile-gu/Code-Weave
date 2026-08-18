package com.codingreview.controlplane.runtime.readiness.model;

/** 平台启动 Coding Run 前必须检查的本机组件。 */
public enum RuntimeComponent {
    RUNTIME_ROOT,
    GIT,
    JAVA,
    MAVEN,
    CLAUDE_CODE
}
