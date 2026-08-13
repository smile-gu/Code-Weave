package com.codingreview.controlplane.adapter.claude;

/** 负责按照启动计划管理 Claude Code 进程生命周期的运行器。 */
public interface ClaudeProcessRunner {

    /**
     * 启动一次 Claude Code 运行，并在读取到事件后立即交给 eventSink。
     *
     * @param launchSpec 已生成的进程启动计划
     * @param eventSink 实时事件接收端
     * @return 进程结束后的结构化运行结论
     */
    ClaudeRunResult run(ClaudeLaunchSpec launchSpec, AgentEventSink eventSink);
}
