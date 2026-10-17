package com.codingreview.controlplane.adapter.claude.invocation;

/** 定义本次调用与 Claude Code 原生会话之间的关系。 */
public enum ClaudeConversationMode {
    /** 独立运行：指定隔离标识且不保存 Claude 上下文。 */
    EPHEMERAL,
    /** Coding Session 首轮：由 Claude 创建并持久化新的原生会话。 */
    START_PERSISTENT,
    /** Coding Session 后续轮：恢复已有原生会话。 */
    RESUME_PERSISTENT
}
