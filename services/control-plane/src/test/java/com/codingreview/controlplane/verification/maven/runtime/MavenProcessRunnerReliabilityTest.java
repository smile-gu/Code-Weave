package com.codingreview.controlplane.verification.maven.runtime;

import com.codingreview.controlplane.observability.run.RunEventType;
import com.codingreview.controlplane.runtime.process.ProcessTreeCleaner;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证 Maven 清理结果不确定时不会发布虚假的取消或终止事实。 */
class MavenProcessRunnerReliabilityTest {
    @TempDir
    Path tempDir;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void cleansKnownChildAfterParentExitsNormally(boolean inherit) throws Exception {
        Path root = tempDir.toAbsolutePath();
        Path gate = root.resolve("release");
        var runner = new MavenProcessRunner(JsonMapper.builder().build(),
                com.codingreview.controlplane.runtime.process.ChildProcessFixture.cleaner(gate));
        try {
            var result = runner.run(new MavenProcessSpec(
                    com.codingreview.controlplane.runtime.process.ChildProcessFixture.command("parent", gate, inherit),
                    root, root.resolve("logs"), root, Duration.ofSeconds(15), Duration.ZERO, 1024 * 1024),
                    (type, payload) -> {});
            long pid = Long.parseLong(Files.readString(root.resolve("child.pid")));
            assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            assertThat(result.status()).isEqualTo(MavenProcessStatus.COMPLETED);
        } finally { com.codingreview.controlplane.runtime.process.ChildProcessFixture.cleanup(gate); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = RunEventType.class,
            names = {"PROCESS_EXIT_OBSERVED", "LOG_CAPTURE_COMPLETED"})
    void cleanupFailureWinsOverFollowingEventFailure(RunEventType failingEvent) throws Exception {
        ProcessTreeCleaner cleaner = new ProcessTreeCleaner() {
            @Override public boolean clean(Process process, Duration grace, Duration timeout) {
                super.clean(process, grace, timeout);
                return false;
            }
        };
        Path root = tempDir.toAbsolutePath();
        var spec = new MavenProcessSpec(List.of("java", "-version"), root, root.resolve("logs"), root,
                Duration.ofSeconds(10), Duration.ZERO, 1024 * 1024);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new MavenProcessRunner(
                JsonMapper.builder().build(), cleaner).run(spec, (type, payload) -> {
                    if (type == failingEvent) throw new IllegalStateException("event failed");
                })).isInstanceOf(com.codingreview.controlplane.runtime.process.ProcessCleanupException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void reportsCleanupFailureWithoutProcessTerminatedEvent() throws Exception {
        ProcessTreeCleaner cleaner = mock(ProcessTreeCleaner.class);
        when(cleaner.clean(any(), any(), any())).thenAnswer(invocation -> {
            Process process = invocation.getArgument(0);
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            return false;
        });
        MavenProcessRunner runner = new MavenProcessRunner(
                JsonMapper.builder().build(), cleaner
        );
        Path runtime = tempDir.resolve("runtime").toAbsolutePath();
        Path workspace = tempDir.resolve("workspace").toAbsolutePath();
        Path artifacts = runtime.resolve("runs/test/artifacts");
        Files.createDirectories(workspace);
        Files.createDirectories(artifacts);
        String encoded = Base64.getEncoder().encodeToString(
                "Start-Sleep -Seconds 30".getBytes(StandardCharsets.UTF_16LE)
        );
        List<RunEventType> events = new ArrayList<>();
        MavenProcessSpec spec = new MavenProcessSpec(
                List.of(Path.of(System.getenv("WINDIR"), "System32", "WindowsPowerShell",
                                "v1.0", "powershell.exe").toString(),
                        "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded),
                workspace, artifacts, runtime,
                Duration.ofMillis(100), Duration.ofMillis(10), 1024 * 1024
        );

        MavenProcessResult result = runner.run(spec, (type, payload) -> events.add(type));

        assertThat(result.status()).isEqualTo(MavenProcessStatus.PROCESS_CLEANUP_FAILED);
        assertThat(events).contains(RunEventType.PROCESS_TERMINATION_REQUESTED)
                .doesNotContain(RunEventType.PROCESS_TERMINATED);
    }
}
