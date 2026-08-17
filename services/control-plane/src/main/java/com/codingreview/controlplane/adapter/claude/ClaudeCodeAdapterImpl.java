package com.codingreview.controlplane.adapter.claude;

import com.codingreview.controlplane.adapter.claude.invocation.CapabilityProfile;
import com.codingreview.controlplane.adapter.claude.invocation.ClaudeCommandFactory;
import com.codingreview.controlplane.adapter.claude.invocation.ClaudeLaunchSpec;
import com.codingreview.controlplane.adapter.claude.invocation.ClaudeRunRequest;
import com.codingreview.controlplane.adapter.claude.runtime.AgentEventSink;
import com.codingreview.controlplane.adapter.claude.runtime.ClaudeProcessRunner;
import com.codingreview.controlplane.adapter.claude.runtime.ClaudeProcessRunnerImpl;
import com.codingreview.controlplane.adapter.claude.runtime.ClaudeRunResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.Objects;

/** 用固定可执行文件和能力配置完成一次 Claude Code 调用。 */
public final class ClaudeCodeAdapterImpl implements ClaudeCodeAdapter {

    private final Path executable;
    private final CapabilityProfile capabilityProfile;
    private final ClaudeCommandFactory commandFactory;
    private final ClaudeProcessRunner processRunner;

    public ClaudeCodeAdapterImpl(
            Path executable,
            CapabilityProfile capabilityProfile,
            ObjectMapper objectMapper
    ) {
        this(
                executable,
                capabilityProfile,
                new ClaudeCommandFactory(),
                new ClaudeProcessRunnerImpl(objectMapper)
        );
    }

    public ClaudeCodeAdapterImpl(
            Path executable,
            CapabilityProfile capabilityProfile,
            ClaudeCommandFactory commandFactory,
            ClaudeProcessRunner processRunner
    ) {
        Objects.requireNonNull(executable, "executable");
        if (!executable.isAbsolute()) {
            throw new IllegalArgumentException("Claude executable path must be absolute");
        }
        this.executable = executable.normalize();
        this.capabilityProfile = Objects.requireNonNull(capabilityProfile, "capabilityProfile");
        this.commandFactory = Objects.requireNonNull(commandFactory, "commandFactory");
        this.processRunner = Objects.requireNonNull(processRunner, "processRunner");
    }

    /** 组装启动规格并执行一次受约束的 Claude Code 任务。 */
    @Override
    public ClaudeRunResult run(ClaudeRunRequest request, AgentEventSink eventSink) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(eventSink, "eventSink");
        ClaudeLaunchSpec launchSpec = commandFactory.create(executable, request, capabilityProfile);
        return processRunner.run(launchSpec, eventSink);
    }
}
