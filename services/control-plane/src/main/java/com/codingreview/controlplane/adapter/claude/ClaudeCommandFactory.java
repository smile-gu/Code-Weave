package com.codingreview.controlplane.adapter.claude;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ClaudeCommandFactory {

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

