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
                .containsSubsequence("--session-id", sessionId.toString())
                .contains("--allowedTools");
        assertThat(spec.command()).doesNotContain(request.taskPrompt(), "Bash");
        assertThat(spec.standardInput()).contains(request.taskPrompt());
        assertThat(spec.workingDirectory()).isEqualTo(request.worktree().normalize());
        assertThat(spec.allowedTools()).containsExactly("Read", "Glob", "Grep", "Edit", "Write");
    }

    @Test
    void startsPersistentConversationWithoutForcingSessionId() {
        ClaudeRunRequest request = request(
                ClaudeConversationMode.START_PERSISTENT, null
        );

        ClaudeLaunchSpec spec = create(request);

        assertThat(spec.command())
                .doesNotContain("--session-id", "--resume", "--no-session-persistence");
    }

    @Test
    void resumesPersistentConversationWithNativeClaudeSessionId() {
        ClaudeRunRequest request = request(
                ClaudeConversationMode.RESUME_PERSISTENT, "claude-native-session"
        );

        ClaudeLaunchSpec spec = create(request);

        assertThat(spec.command())
                .containsSubsequence("--resume", "claude-native-session")
                .doesNotContain("--session-id", "--no-session-persistence");
    }

    /** 创建指定原生会话模式的测试请求。 */
    private ClaudeRunRequest request(ClaudeConversationMode mode, String nativeId) {
        return new ClaudeRunRequest(
                UUID.randomUUID(), Path.of("D:/runtime/worktrees/session"),
                "继续完成用户模块", List.of(), List.of("src/**"), List.of(".git/**"),
                mode, nativeId
        );
    }

    /** 使用 M6 能力配置创建启动规格。 */
    private ClaudeLaunchSpec create(ClaudeRunRequest request) {
        return new ClaudeCommandFactory().create(
                Path.of("D:/develop/nodejs/claude.cmd"), request,
                CapabilityProfile.m6SessionRestricted()
        );
    }
}
