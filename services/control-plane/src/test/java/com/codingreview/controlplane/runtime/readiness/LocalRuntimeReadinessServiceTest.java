package com.codingreview.controlplane.runtime.readiness;

import com.codingreview.controlplane.configuration.CodeWeaveProperties;
import com.codingreview.controlplane.runtime.readiness.model.RuntimeCheckStatus;
import com.codingreview.controlplane.runtime.readiness.model.RuntimeComponent;
import com.codingreview.controlplane.runtime.readiness.model.RuntimeReadiness;
import com.codingreview.controlplane.runtime.readiness.model.RuntimeReadinessStatus;
import com.codingreview.controlplane.runtime.readiness.probe.CommandProbe;
import com.codingreview.controlplane.runtime.readiness.probe.CommandProbeResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalRuntimeReadinessServiceTest {

    private static final String CLAUDE_HELP = """
            --print --input-format --output-format --verbose --permission-mode
            --session-id --resume --max-budget-usd --safe-mode --no-session-persistence
            --tools --allowedTools
            """;

    @TempDir
    Path temporaryDirectory;

    @Test
    void reportsReadyWhenAllRequiredComponentsAreCompatible() throws Exception {
        TestEnvironment environment = environment("claude.cmd");
        RuntimeReadiness readiness = environment.service(successfulProbe(CLAUDE_HELP)).check();

        assertThat(readiness.status()).isEqualTo(RuntimeReadinessStatus.READY);
        assertThat(readiness.checks()).hasSize(5).allMatch(check -> !check.blocking());
        assertThat(readiness.checks())
                .filteredOn(check -> check.component() == RuntimeComponent.CLAUDE_CODE)
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.version()).isEqualTo("2.1.217");
                    assertThat(check.status()).isEqualTo(RuntimeCheckStatus.PASSED);
                });
    }

    @Test
    void rejectsPowerShellClaudeLauncherOnWindows() throws Exception {
        TestEnvironment environment = environment("claude.ps1");
        RuntimeReadiness readiness = environment.service(successfulProbe(CLAUDE_HELP)).check();

        assertThat(readiness.status()).isEqualTo(RuntimeReadinessStatus.NOT_READY);
        assertThat(readiness.checks())
                .filteredOn(check -> check.component() == RuntimeComponent.CLAUDE_CODE)
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.status()).isEqualTo(RuntimeCheckStatus.FAILED);
                    assertThat(check.remediation()).contains("claude.cmd");
                });
    }

    @Test
    void rejectsClaudeCodeWhenRequiredProtocolOptionIsMissing() throws Exception {
        TestEnvironment environment = environment("claude.cmd");
        RuntimeReadiness readiness = environment.service(
                successfulProbe(CLAUDE_HELP.replace("--resume", ""))
        ).check();

        assertThat(readiness.status()).isEqualTo(RuntimeReadinessStatus.NOT_READY);
        assertThat(readiness.checks())
                .filteredOn(check -> check.component() == RuntimeComponent.CLAUDE_CODE)
                .singleElement()
                .satisfies(check -> assertThat(check.remediation()).contains("--resume"));
    }

    @Test
    void rejectsMissingPathToolWithoutExecutingIt() throws Exception {
        TestEnvironment environment = environment("claude.cmd");
        Files.delete(environment.git());

        RuntimeReadiness readiness = environment.service(successfulProbe(CLAUDE_HELP)).check();

        assertThat(readiness.status()).isEqualTo(RuntimeReadinessStatus.NOT_READY);
        assertThat(readiness.checks())
                .filteredOn(check -> check.component() == RuntimeComponent.GIT)
                .singleElement()
                .satisfies(check -> assertThat(check.message()).contains("未找到"));
    }

    /** 创建包含可解析工具入口和运行目录的测试环境。 */
    private TestEnvironment environment(String claudeName) throws Exception {
        Path tools = Files.createDirectory(temporaryDirectory.resolve("tools"));
        Path runtime = Files.createDirectory(temporaryDirectory.resolve("runtime"));
        Path git = Files.createFile(tools.resolve("git.exe"));
        Files.createFile(tools.resolve("java.exe"));
        Files.createFile(tools.resolve("mvn.cmd"));
        Path claude = Files.createFile(tools.resolve(claudeName));

        CodeWeaveProperties properties = new CodeWeaveProperties();
        properties.setRuntimeRoot(runtime);
        properties.getClaude().setExecutable(claude);
        return new TestEnvironment(properties, tools, git);
    }

    /** 创建按命令名称返回稳定版本和帮助文本的假探测器。 */
    private CommandProbe successfulProbe(String claudeHelp) {
        return (command, timeout) -> {
            assertThat(timeout).isEqualTo(Duration.ofSeconds(5));
            String executable = Path.of(command.getFirst()).getFileName().toString().toLowerCase();
            String output;
            if (executable.startsWith("git")) {
                output = "git version 2.51.1.windows.1";
            } else if (executable.startsWith("java")) {
                output = "openjdk version \"21.0.12\" 2026-07-21 LTS";
            } else if (executable.startsWith("mvn")) {
                output = "Apache Maven 3.9.11";
            } else if (command.contains("--help")) {
                output = claudeHelp;
            } else {
                output = "2.1.217 (Claude Code)";
            }
            return new CommandProbeResult(true, false, 0, output, false, null);
        };
    }

    /** 保存测试配置和模拟 PATH 目录。 */
    private record TestEnvironment(
            CodeWeaveProperties properties,
            Path tools,
            Path git
    ) {
        /** 使用当前测试环境创建 Windows Readiness Service。 */
        LocalRuntimeReadinessService service(CommandProbe probe) {
            return new LocalRuntimeReadinessService(
                    properties,
                    probe,
                    tools.toString() + File.pathSeparator,
                    "Windows 11"
            );
        }
    }
}
