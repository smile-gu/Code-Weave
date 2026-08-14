package com.codingreview.controlplane.adapter.claude.invocation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 根据运行请求和能力配置生成 Claude Code 进程启动规格。 */
public final class ClaudeCommandFactory {

    /**
     * 参数以列表形式交给进程启动层，动态内容不会拼接成 Shell 命令。
     */
    public ClaudeLaunchSpec create(Path executable, ClaudeRunRequest request, CapabilityProfile profile) {
        if (!executable.isAbsolute()) {
            throw new IllegalArgumentException("Claude executable path must be absolute");
        }

        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.add("--print");
        command.add("--input-format");
        command.add("text");
        command.add("--output-format");
        command.add("stream-json");
        command.add("--verbose");
        command.add("--permission-mode");
        command.add(profile.permissionMode());
        command.add("--session-id");
        command.add(request.sessionId().toString());
        command.add("--max-budget-usd");
        command.add(Double.toString(profile.maxBudgetUsd()));

        if (profile.safeMode()) {
            command.add("--safe-mode");
        }
        if (!profile.sessionPersistence()) {
            command.add("--no-session-persistence");
        }
        command.add("--tools");
        command.addAll(profile.tools());
        command.add("--allowedTools");
        command.addAll(profile.tools());

        return new ClaudeLaunchSpec(
                command,
                request.worktree().normalize(),
                ClaudePromptRenderer.render(request),
                profile.startupTimeout(),
                profile.taskTimeout(),
                profile.cancellationGracePeriod()
        );
    }
}
