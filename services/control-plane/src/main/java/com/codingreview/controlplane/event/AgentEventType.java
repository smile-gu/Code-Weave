package com.codingreview.controlplane.event;

/** Control Plane 对不同 Agent Runtime 暴露的统一事件类型。 */
public enum AgentEventType {
    /** Agent Runtime 已完成初始化。 */
    RUNTIME_INITIALIZED,
    /** Agent 的 Token 或费用使用量发生更新。 */
    USAGE_UPDATED,
    /** Agent 输出了一段完整的普通文本消息。 */
    MESSAGE_COMPLETED,
    /** Agent 发起了一次工具调用。 */
    TOOL_CALL_STARTED,
    /** 工具调用成功返回结果。 */
    TOOL_CALL_COMPLETED,
    /** 工具调用返回错误，但 Agent Runtime 仍可能继续运行。 */
    TOOL_CALL_FAILED,
    /** 工具调用因权限策略被平台拒绝。 */
    PERMISSION_DENIED,
    /** Agent Runtime 正常完成并产生终态结果。 */
    RUNTIME_COMPLETED,
    /** Agent Runtime 以失败状态结束。 */
    RUNTIME_FAILED,
    /** 平台产生的诊断信息，用于排查运行问题。 */
    DIAGNOSTIC
}
