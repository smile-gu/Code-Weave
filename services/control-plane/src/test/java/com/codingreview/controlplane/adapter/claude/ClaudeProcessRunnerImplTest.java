package com.codingreview.controlplane.adapter.claude;

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
        String script = "$lines = @(" +
                "'{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s-1\"}'," +
                "'{\"type\":\"result\",\"subtype\":\"success\",\"session_id\":\"s-1\"}'" +
                "); $lines | ForEach-Object { Write-Output $_ }";
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
        String script = "Write-Output '{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s-1\"}'; Start-Sleep -Seconds 3";

        ClaudeRunResult result = runner.run(spec(script, Duration.ofSeconds(2), Duration.ofSeconds(5)),
                event -> {
                    throw new IllegalStateException("event store unavailable");
                });

        assertThat(result.status()).isEqualTo(ClaudeRunStatus.EVENT_PUBLISH_FAILED);
        assertThat(result.diagnostic()).contains("event store unavailable");
    }

    @Test
    void terminatesProcessWhenTaskTimesOut() throws Exception {
        String script = "$lines = @('{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s-1\"}'); $lines | ForEach-Object { Write-Output $_ }; Start-Sleep -Seconds 3";
        ClaudeRunResult result = runner.run(spec(script, Duration.ofSeconds(2), Duration.ofMillis(100)),
                event -> {
                });

        assertThat(result.status()).isEqualTo(ClaudeRunStatus.TIMED_OUT);
    }

    @Test
    void startsTaskTimeoutAfterRuntimeInitialization() throws Exception {
        String script = "Start-Sleep -Milliseconds 600; " +
                "Write-Output '{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s-1\"}'; " +
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
                "Write-Output '{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s-1\"}'; " +
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

    private ClaudeLaunchSpec spec(String script, Duration startupTimeout, Duration taskTimeout) throws Exception {
        return spec(script, createWorktree(), startupTimeout, taskTimeout);
    }

    private ClaudeLaunchSpec spec(String script, Path worktree,
                                  Duration startupTimeout, Duration taskTimeout) {
        String encodedScript = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        return new ClaudeLaunchSpec(
                List.of(POWERSHELL.toString(), "-NoProfile", "-NonInteractive", "-EncodedCommand", encodedScript),
                worktree,
                "test prompt",
                startupTimeout,
                taskTimeout,
                Duration.ofMillis(100)
        );
    }

    private Path createWorktree() throws Exception {
        Path testRuns = Path.of("target", "claude-runner-tests").toAbsolutePath();
        Files.createDirectories(testRuns);
        return Files.createTempDirectory(testRuns, "run-");
    }

    private final List<AgentEvent> events = new ArrayList<>();
}
