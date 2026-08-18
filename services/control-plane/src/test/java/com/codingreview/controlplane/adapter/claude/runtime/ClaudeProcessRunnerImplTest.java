package com.codingreview.controlplane.adapter.claude.runtime;

import com.codingreview.controlplane.adapter.claude.invocation.ClaudeLaunchSpec;

import com.codingreview.controlplane.event.AgentEvent;
import com.codingreview.controlplane.event.AgentEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 使用本机命令验证 Claude 进程运行器的流式读取和生命周期判定。 */
class ClaudeProcessRunnerImplTest {

    private static final Path POWERSHELL = Path.of(
            System.getenv("WINDIR"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe"
    );

    private final ClaudeProcessRunnerImpl runner = new ClaudeProcessRunnerImpl(new ObjectMapper());

    @Test
    void publishesEventsAndReturnsCompletedResult() throws Exception {
        String script = "$lines = @('" + initializedEvent(List.of("Read", "Edit")) + "',"
                + "'{\"type\":\"result\",\"subtype\":\"success\",\"session_id\":\"s-1\"}'); "
                + "$lines | ForEach-Object { Write-Output $_ }";
        ClaudeRunResult result = runner.run(spec(script, Duration.ofSeconds(2), Duration.ofSeconds(5)),
                events::add);

        assertThat(result.status()).isEqualTo(ClaudeRunStatus.COMPLETED);
        assertThat(result.exitCode()).isZero();
        assertThat(result.initialized()).isTrue();
        assertThat(result.terminalResultReceived()).isTrue();
        assertThat(events).extracting(AgentEvent::type)
                .containsExactly(AgentEventType.RUNTIME_INITIALIZED, AgentEventType.RUNTIME_COMPLETED);
    }

    @Test
    void dropsHighFrequencyUsageUpdatesButKeepsTerminalUsage() throws Exception {
        String usageUpdate = "{\"type\":\"system\",\"subtype\":\"thinking_tokens\","
                + "\"estimated_tokens\":42,\"estimated_tokens_delta\":1,\"uuid\":\"usage-1\"}";
        String terminalResult = "{\"type\":\"result\",\"subtype\":\"success\","
                + "\"session_id\":\"s-1\",\"total_cost_usd\":0.11,"
                + "\"usage\":{\"input_tokens\":100,\"output_tokens\":20}}";
        String script = "Write-Output '" + initializedEvent(List.of("Read")) + "'; "
                + "1..200 | ForEach-Object { Write-Output '" + usageUpdate + "' }; "
                + "Write-Output '" + terminalResult + "'";

        ClaudeRunResult result = runner.run(
                spec(script, Duration.ofSeconds(2), Duration.ofSeconds(5)),
                events::add
        );

        assertThat(result.status()).isEqualTo(ClaudeRunStatus.COMPLETED);
        assertThat(events).extracting(AgentEvent::type)
                .containsExactly(AgentEventType.RUNTIME_INITIALIZED, AgentEventType.RUNTIME_COMPLETED);
        assertThat(result.terminalEvent().payload().path("total_cost_usd").decimalValue())
                .isEqualByComparingTo("0.11");
        assertThat(result.terminalEvent().payload().path("usage").path("input_tokens").asInt())
                .isEqualTo(100);
        assertThat(result.terminalEvent().payload().path("usage").path("output_tokens").asInt())
                .isEqualTo(20);
    }

    @Test
    void returnsProtocolErrorForMalformedOutput() throws Exception {
        ClaudeRunResult result = runner.run(spec("Write-Output 'not-json'", Duration.ofSeconds(2), Duration.ofSeconds(5)),
                event -> {
                });

        assertThat(result.status()).isEqualTo(ClaudeRunStatus.PROTOCOL_ERROR);
        assertThat(result.terminalResultReceived()).isFalse();
        assertThat(result.diagnostic()).contains("Invalid Claude stream JSON");
    }

    @Test
    void doesNotWaitForStartupTimeoutAfterProcessExits() throws Exception {
        Instant startedAt = Instant.now();

        ClaudeRunResult result = runner.run(spec("exit 0", Duration.ofSeconds(5), Duration.ofSeconds(5)),
                event -> {
                });

        assertThat(result.status()).isEqualTo(ClaudeRunStatus.INCOMPLETE_STREAM);
        assertThat(Duration.between(startedAt, Instant.now())).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void terminatesRunWhenEventSinkFails() throws Exception {
        String script = "Write-Output '" + initializedEvent(List.of("Read"))
                + "'; Start-Sleep -Seconds 3";

        ClaudeRunResult result = runner.run(spec(script, Duration.ofSeconds(2), Duration.ofSeconds(5)),
                event -> {
                    throw new IllegalStateException("event store unavailable");
                });

        assertThat(result.status()).isEqualTo(ClaudeRunStatus.EVENT_PUBLISH_FAILED);
        assertThat(result.diagnostic()).contains("event store unavailable");
    }

    @Test
    void terminatesProcessWhenTaskTimesOut() throws Exception {
        String script = "Write-Output '" + initializedEvent(List.of("Read"))
                + "'; Start-Sleep -Seconds 3";
        ClaudeRunResult result = runner.run(spec(script, Duration.ofSeconds(2), Duration.ofMillis(100)),
                event -> {
                });

        assertThat(result.status()).isEqualTo(ClaudeRunStatus.TIMED_OUT);
    }

    @Test
    void startsTaskTimeoutAfterRuntimeInitialization() throws Exception {
        String script = "Start-Sleep -Milliseconds 600; " +
                "Write-Output '" + initializedEvent(List.of("Read")) + "'; " +
                "Start-Sleep -Milliseconds 100; " +
                "Write-Output '{\"type\":\"result\",\"subtype\":\"success\",\"session_id\":\"s-1\"}'";

        ClaudeRunResult result = runner.run(spec(script, Duration.ofSeconds(2), Duration.ofMillis(500)),
                event -> {
                });

        assertThat(result.status()).isEqualTo(ClaudeRunStatus.COMPLETED);
    }

    @Test
    void terminatesChildProcessBeforeReturningFromTimeout() throws Exception {
        Path worktree = createWorktree();
        Path pidFile = worktree.resolve("child.pid");
        String escapedPidFile = pidFile.toString().replace("'", "''");
        String script = "$child = Start-Process powershell.exe -PassThru -ArgumentList " +
                "'-NoProfile','-NonInteractive','-Command','Start-Sleep -Seconds 30'; " +
                "$child.Id | Set-Content -Encoding ascii '" + escapedPidFile + "'; " +
                "Write-Output '" + initializedEvent(List.of("Read")) + "'; " +
                "Start-Sleep -Seconds 30";

        ClaudeRunResult result = runner.run(
                spec(script, worktree, Duration.ofSeconds(2), Duration.ofMillis(200)),
                event -> {
                }
        );

        long childPid = Long.parseLong(Files.readString(pidFile, StandardCharsets.US_ASCII).trim());
        assertThat(result.status()).isEqualTo(ClaudeRunStatus.TIMED_OUT);
        assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    @Test
    void terminatesWhenClaudeInitializesOutsideManagedWorktree() throws Exception {
        String script = "Write-Output '{\"type\":\"system\",\"subtype\":\"init\","
                + "\"session_id\":\"s-1\",\"cwd\":\"C:/unexpected\"}'; "
                + "Start-Sleep -Seconds 3";

        ClaudeRunResult result = runner.run(
                spec(script, Duration.ofSeconds(2), Duration.ofSeconds(5)),
                events::add
        );

        assertThat(result.status()).isEqualTo(ClaudeRunStatus.WORKSPACE_MISMATCH);
        assertThat(result.diagnostic()).contains("C:/unexpected");
        assertThat(events).extracting(AgentEvent::type)
                .containsExactly(AgentEventType.RUNTIME_INITIALIZED);
    }

    @Test
    void terminatesWhenInitializationExposesUnauthorizedTool() throws Exception {
        String script = "Write-Output '" + initializedEvent(List.of("Read", "Bash"))
                + "'; Start-Sleep -Seconds 3";

        ClaudeRunResult result = runner.run(
                spec(script, Duration.ofSeconds(2), Duration.ofSeconds(5)),
                events::add
        );

        assertThat(result.status()).isEqualTo(ClaudeRunStatus.TOOL_POLICY_VIOLATION);
        assertThat(result.diagnostic()).contains("Bash");
        assertThat(events).extracting(AgentEvent::type)
                .containsExactly(AgentEventType.RUNTIME_INITIALIZED);
    }

    @Test
    void publishesUnauthorizedToolCallBeforeTerminating() throws Exception {
        String toolCall = "{\"type\":\"assistant\",\"uuid\":\"message-1\",\"message\":{"
                + "\"content\":[{\"type\":\"tool_use\",\"id\":\"call-1\",\"name\":\"Bash\"}]}}";
        String script = "$lines = @('" + initializedEvent(List.of("Read")) + "','" + toolCall
                + "'); $lines | ForEach-Object { Write-Output $_ }; Start-Sleep -Seconds 3";

        ClaudeRunResult result = runner.run(
                spec(script, Duration.ofSeconds(2), Duration.ofSeconds(5)),
                event -> {
                    events.add(event);
                    if (event.type() == AgentEventType.TOOL_CALL_STARTED) {
                        ((com.fasterxml.jackson.databind.node.ObjectNode) event.payload())
                                .put("name", "Read");
                    }
                }
        );

        assertThat(result.status()).isEqualTo(ClaudeRunStatus.TOOL_POLICY_VIOLATION);
        assertThat(events).extracting(AgentEvent::type)
                .containsExactly(AgentEventType.RUNTIME_INITIALIZED, AgentEventType.TOOL_CALL_STARTED);
    }

    @Test
    void eventPublishingFailureWinsOverToolPolicyViolation() throws Exception {
        String toolCall = "{\"type\":\"assistant\",\"uuid\":\"message-1\",\"message\":{"
                + "\"content\":[{\"type\":\"tool_use\",\"id\":\"call-1\",\"name\":\"Bash\"}]}}";
        String script = "$lines = @('" + initializedEvent(List.of("Read")) + "','" + toolCall
                + "'); $lines | ForEach-Object { Write-Output $_ }; Start-Sleep -Seconds 3";

        ClaudeRunResult result = runner.run(
                spec(script, Duration.ofSeconds(2), Duration.ofSeconds(5)),
                event -> {
                    if (event.type() == AgentEventType.TOOL_CALL_STARTED) {
                        throw new IllegalStateException("event journal unavailable");
                    }
                }
        );

        assertThat(result.status()).isEqualTo(ClaudeRunStatus.EVENT_PUBLISH_FAILED);
        assertThat(result.diagnostic()).contains("event journal unavailable");
    }

    private ClaudeLaunchSpec spec(String script, Duration startupTimeout, Duration taskTimeout) throws Exception {
        return spec(script, createWorktree(), startupTimeout, taskTimeout);
    }

    private ClaudeLaunchSpec spec(String script, Path worktree,
                                  Duration startupTimeout, Duration taskTimeout) {
        String escapedWorktree = worktree.toString()
                .replace("\\", "\\\\")
                .replace("'", "''");
        script = script.replace("__WORKTREE__", escapedWorktree);
        String encodedScript = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        return new ClaudeLaunchSpec(
                List.of(POWERSHELL.toString(), "-NoProfile", "-NonInteractive", "-EncodedCommand", encodedScript),
                worktree,
                "test prompt",
                List.of("Read", "Glob", "Grep", "Edit", "Write"),
                startupTimeout,
                taskTimeout,
                Duration.ofMillis(100)
        );
    }

    private String initializedEvent(List<String> tools) {
        String serializedTools = tools.stream()
                .map(tool -> "\"" + tool + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s-1\","
                + "\"cwd\":\"__WORKTREE__\",\"tools\":[" + serializedTools + "]}";
    }

    private Path createWorktree() throws Exception {
        Path testRuns = Path.of("target", "claude-runner-tests").toAbsolutePath();
        Files.createDirectories(testRuns);
        return Files.createTempDirectory(testRuns, "run-");
    }

    private final List<AgentEvent> events = new ArrayList<>();
}
