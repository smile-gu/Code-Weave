package com.codingreview.controlplane.runtime.readiness.probe;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalCommandProbeTest {

    private final LocalCommandProbe probe = new LocalCommandProbe();

    @Test
    void capturesSuccessfulCommandOutput() {
        CommandProbeResult result = probe.execute(fixtureCommand("success"), Duration.ofSeconds(3));

        assertThat(result.successful()).isTrue();
        assertThat(result.output()).contains("probe-ready");
        assertThat(result.outputTruncated()).isFalse();
    }

    @Test
    void terminatesCommandAfterTimeout() {
        CommandProbeResult result = probe.execute(fixtureCommand("sleep"), Duration.ofMillis(100));

        assertThat(result.started()).isTrue();
        assertThat(result.timedOut()).isTrue();
        assertThat(result.successful()).isFalse();
    }

    @Test
    void drainsButBoundsLargeCommandOutput() {
        CommandProbeResult result = probe.execute(fixtureCommand("large"), Duration.ofSeconds(3));

        assertThat(result.successful()).isTrue();
        assertThat(result.outputTruncated()).isTrue();
        assertThat(result.output().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .hasSize(LocalCommandProbe.MAX_OUTPUT_BYTES);
    }

    @Test
    void reportsStartFailureWithoutThrowing() {
        CommandProbeResult result = probe.execute(
                List.of("Z:/missing/code-weave-probe.exe", "--version"),
                Duration.ofSeconds(1)
        );

        assertThat(result.started()).isFalse();
        assertThat(result.successful()).isFalse();
        assertThat(result.diagnostic()).isNotBlank();
    }

    /** 创建调用当前测试进程内探测 Fixture 的 Java 命令。 */
    private List<String> fixtureCommand(String mode) {
        String executable = Path.of(
                System.getProperty("java.home"),
                "bin",
                isWindows() ? "java.exe" : "java"
        ).toString();
        return List.of(
                executable,
                "-cp",
                System.getProperty("java.class.path"),
                ProbeFixture.class.getName(),
                mode
        );
    }

    /** 判断测试进程是否运行在 Windows。 */
    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** 为命令探测器测试提供可控的独立 Java 进程。 */
    public static final class ProbeFixture {

        /** 根据测试模式输出、休眠或生成超大文本。 */
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "success" -> System.out.println("probe-ready");
                case "sleep" -> Thread.sleep(5_000);
                case "large" -> System.out.print("x".repeat(LocalCommandProbe.MAX_OUTPUT_BYTES + 100));
                default -> System.exit(2);
            }
        }
    }
}
