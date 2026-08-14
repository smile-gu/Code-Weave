package com.codingreview.controlplane.adapter.claude.invocation;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 M0 命令参数、能力限制以及 Prompt 的传递方式。 */
class ClaudeCommandFactoryTest {

    @Test
    void createsRestrictedM0CommandAndKeepsPromptOnStdin() {
        UUID sessionId = UUID.randomUUID();
        ClaudeRunRequest request = new ClaudeRunRequest(
                sessionId,
                Path.of("D:/runtime/worktrees/run-1"),
                "Add a greeting endpoint",
                List.of("GET /api/greeting returns 200"),
                List.of("src/**", "pom.xml"),
                List.of(".git/**")
        );

        ClaudeLaunchSpec spec = new ClaudeCommandFactory().create(
                Path.of("D:/develop/nodejs/claude.cmd"),
                request,
                CapabilityProfile.m0Restricted()
        );

        assertThat(spec.command())
                .contains("--safe-mode", "--no-session-persistence", "--max-budget-usd", "1.0")
                .containsSubsequence("--output-format", "stream-json")
                .containsSubsequence("--permission-mode", "dontAsk")
                .containsSubsequence("--session-id", sessionId.toString());
        assertThat(spec.command()).doesNotContain(request.taskPrompt(), "Bash");
        assertThat(spec.standardInput()).contains(request.taskPrompt());
        assertThat(spec.workingDirectory()).isEqualTo(request.worktree().normalize());
        assertThat(spec.allowedTools()).containsExactly("Read", "Glob", "Grep", "Edit", "Write");
    }
}
