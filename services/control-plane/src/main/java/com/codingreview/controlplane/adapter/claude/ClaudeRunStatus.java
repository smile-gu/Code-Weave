package com.codingreview.controlplane.adapter.claude;

/** Claude 进程一次运行的最终状态。 */
public enum ClaudeRunStatus {
    /** 收到成功终态事件且进程正常退出。 */
    COMPLETED,
    /** Claude 返回失败终态。 */
    AGENT_FAILED,
    /** 进程以非零退出码结束。 */
    PROCESS_FAILED,
    /** 进程无法启动。 */
    START_FAILED,
    /** 在启动超时时间内没有完成初始化。 */
    START_TIMEOUT,
    /** Claude 输出不符合约定协议。 */
    PROTOCOL_ERROR,
    /** 平台无法可靠发布 Agent 事件，因此主动终止运行。 */
    EVENT_PUBLISH_FAILED,
    /** 进程退出前没有收到终态事件。 */
    INCOMPLETE_STREAM,
    /** 运行超过任务超时时间。 */
    TIMED_OUT,
    /** 用户主动取消运行。 */
    CANCELLED
}
