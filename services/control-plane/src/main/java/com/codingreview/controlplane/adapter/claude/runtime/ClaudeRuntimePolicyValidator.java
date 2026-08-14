package com.codingreview.controlplane.adapter.claude.runtime;

import com.codingreview.controlplane.adapter.claude.invocation.ClaudeLaunchSpec;
import com.codingreview.controlplane.event.AgentEvent;
import com.codingreview.controlplane.event.AgentEventType;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** 校验 Claude 实际工作目录和工具调用是否符合启动契约。 */
final class ClaudeRuntimePolicyValidator {

    Optional<ClaudeRuntimePolicyViolation> validate(AgentEvent event, ClaudeLaunchSpec spec) {
        if (event.type() == AgentEventType.RUNTIME_INITIALIZED) {
            Optional<ClaudeRuntimePolicyViolation> workspaceViolation = validateWorkspace(event, spec);
            if (workspaceViolation.isPresent()) {
                return workspaceViolation;
            }
            return validateInitializedTools(event, spec);
        }
        if (event.type() == AgentEventType.TOOL_CALL_STARTED) {
            return validateToolCall(event, spec);
        }
        return Optional.empty();
    }

    private Optional<ClaudeRuntimePolicyViolation> validateWorkspace(
            AgentEvent event,
            ClaudeLaunchSpec spec
    ) {
        String reportedCwd = event.payload().path("cwd").asText(null);
        if (reportedCwd == null || reportedCwd.isBlank()) {
            return workspaceViolation("Claude init event did not provide cwd");
        }
        try {
            Path actual = Path.of(reportedCwd);
            if (!actual.isAbsolute() || !Files.isSameFile(spec.workingDirectory(), actual)) {
                return workspaceViolation(
                        "Claude initialized in unexpected cwd: expected="
                                + spec.workingDirectory() + ", actual=" + actual
                );
            }
            return Optional.empty();
        } catch (InvalidPathException | IOException | SecurityException exception) {
            return workspaceViolation(
                    "Unable to verify Claude cwd '" + reportedCwd + "': " + exception.getMessage()
            );
        }
    }

    private Optional<ClaudeRuntimePolicyViolation> validateInitializedTools(
            AgentEvent event,
            ClaudeLaunchSpec spec
    ) {
        JsonNode tools = event.payload().get("tools");
        if (tools == null || tools.isNull()) {
            return Optional.empty();
        }
        if (!tools.isArray()) {
            return toolViolation("Claude init tools field is not an array");
        }
        Set<String> allowed = new HashSet<>(spec.allowedTools());
        for (JsonNode tool : tools) {
            if (!tool.isTextual() || !allowed.contains(tool.asText())) {
                return toolViolation("Claude init exposed unauthorized tool: " + tool.asText());
            }
        }
        return Optional.empty();
    }

    private Optional<ClaudeRuntimePolicyViolation> validateToolCall(
            AgentEvent event,
            ClaudeLaunchSpec spec
    ) {
        String toolName = event.payload().path("name").asText(null);
        if (toolName == null || toolName.isBlank() || !spec.allowedTools().contains(toolName)) {
            return toolViolation("Claude attempted unauthorized tool: " + toolName);
        }
        return Optional.empty();
    }

    private Optional<ClaudeRuntimePolicyViolation> workspaceViolation(String diagnostic) {
        return Optional.of(new ClaudeRuntimePolicyViolation(
                ClaudeRunStatus.WORKSPACE_MISMATCH,
                diagnostic
        ));
    }

    private Optional<ClaudeRuntimePolicyViolation> toolViolation(String diagnostic) {
        return Optional.of(new ClaudeRuntimePolicyViolation(
                ClaudeRunStatus.TOOL_POLICY_VIOLATION,
                diagnostic
        ));
    }
}
