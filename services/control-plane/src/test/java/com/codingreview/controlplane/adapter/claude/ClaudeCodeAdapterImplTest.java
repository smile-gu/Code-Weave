package com.codingreview.controlplane.adapter.claude;

import com.codingreview.controlplane.adapter.claude.invocation.CapabilityProfile;
import com.codingreview.controlplane.adapter.claude.invocation.ClaudeCommandFactory;
import com.codingreview.controlplane.adapter.claude.invocation.ClaudeLaunchSpec;
import com.codingreview.controlplane.adapter.claude.invocation.ClaudeRunRequest;
import com.codingreview.controlplane.adapter.claude.runtime.ClaudeProcessRunner;
import com.codingreview.controlplane.adapter.claude.runtime.ClaudeRunResult;
import com.codingreview.controlplane.adapter.claude.runtime.ClaudeRunStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证 Adapter 门面能够隐藏命令生成并传递冻结的运行契约。 */
class ClaudeCodeAdapterImplTest {

    @Test
    void createsLaunchSpecAndDelegatesToRunner() {
        AtomicReference<ClaudeLaunchSpec> capturedSpec = new AtomicReference<>();
        ClaudeRunResult expected = completedResult();
        ClaudeProcessRunner runner = (spec, eventSink) -> {
            capturedSpec.set(spec);
            return expected;
        };
        ClaudeCodeAdapter adapter = new ClaudeCodeAdapterImpl(
                Path.of("D:/tools/claude.cmd"),
                CapabilityProfile.codingSessionRestricted(),
                new ClaudeCommandFactory(),
                runner
        );
        ClaudeRunRequest request = request();

        ClaudeRunResult actual = adapter.run(request, event -> {
        });

        assertThat(actual).isSameAs(expected);
        assertThat(capturedSpec.get().workingDirectory()).isEqualTo(request.workspaceRoot().normalize());
        assertThat(capturedSpec.get().standardInput()).contains(request.taskPrompt());
        assertThat(capturedSpec.get().allowedTools())
                .containsExactly("Read", "Glob", "Grep", "Edit", "Write");
    }

    @Test
    void rejectsRelativeExecutableAtConfigurationBoundary() {
        assertThatThrownBy(() -> new ClaudeCodeAdapterImpl(
                Path.of("claude.cmd"),
                CapabilityProfile.codingSessionRestricted(),
                new ClaudeCommandFactory(),
                (spec, eventSink) -> completedResult()
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private ClaudeRunRequest request() {
        return new ClaudeRunRequest(
                UUID.randomUUID(),
                Path.of("D:/runtime/worktrees/run-1"),
                "Add a greeting endpoint",
                List.of("Tests pass"),
                List.of("src/**"),
                List.of(".git/**")
        );
    }

    private static ClaudeRunResult completedResult() {
        Instant startedAt = Instant.parse("2026-08-14T03:00:00Z");
        return new ClaudeRunResult(
                ClaudeRunStatus.COMPLETED,
                0,
                true,
                false,
                null,
                startedAt,
                startedAt.plusSeconds(1),
                null
        );
    }
}
