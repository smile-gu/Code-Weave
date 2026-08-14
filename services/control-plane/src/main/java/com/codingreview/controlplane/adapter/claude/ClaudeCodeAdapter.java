package com.codingreview.controlplane.adapter.claude;

import com.codingreview.controlplane.adapter.claude.invocation.ClaudeRunRequest;
import com.codingreview.controlplane.adapter.claude.runtime.AgentEventSink;
import com.codingreview.controlplane.adapter.claude.runtime.ClaudeRunResult;

/** 工作流调用 Claude Code 的统一入口，隐藏命令和进程协议细节。 */
public interface ClaudeCodeAdapter {

    ClaudeRunResult run(ClaudeRunRequest request, AgentEventSink eventSink);
}
