package com.codingreview.controlplane.runtime.readiness;

import com.codingreview.controlplane.configuration.CodeWeaveProperties;
import com.codingreview.controlplane.runtime.readiness.model.RuntimeCheck;
import com.codingreview.controlplane.runtime.readiness.model.RuntimeCheckStatus;
import com.codingreview.controlplane.runtime.readiness.model.RuntimeComponent;
import com.codingreview.controlplane.runtime.readiness.model.RuntimeReadiness;
import com.codingreview.controlplane.runtime.readiness.probe.CommandProbe;
import com.codingreview.controlplane.runtime.readiness.probe.CommandProbeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 实时检查 Control Plane 执行 Coding Run 所需的本机环境。 */
public final class LocalRuntimeReadinessService implements RuntimeReadinessService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalRuntimeReadinessService.class);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);
    private static final SemanticVersion MIN_GIT_VERSION = new SemanticVersion(2, 40, 0);
    private static final SemanticVersion MIN_JAVA_VERSION = new SemanticVersion(21, 0, 0);
    private static final SemanticVersion MIN_MAVEN_VERSION = new SemanticVersion(3, 9, 0);
    private static final SemanticVersion MIN_CLAUDE_VERSION = new SemanticVersion(2, 1, 0);
    private static final Pattern GIT_VERSION = Pattern.compile("git version\\s+(\\d+)\\.(\\d+)\\.(\\d+)");
    private static final Pattern JAVA_VERSION = Pattern.compile("version \\\"(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");
    private static final Pattern MAVEN_VERSION = Pattern.compile("Apache Maven\\s+(\\d+)\\.(\\d+)\\.(\\d+)");
    private static final Pattern CLAUDE_VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");
    private static final List<String> REQUIRED_CLAUDE_OPTIONS = List.of(
            "--print",
            "--input-format",
            "--output-format",
            "--verbose",
            "--permission-mode",
            "--session-id",
            "--resume",
            "--max-budget-usd",
            "--safe-mode",
            "--no-session-persistence",
            "--tools",
            "--allowedTools"
    );

    private final CodeWeaveProperties properties;
    private final CommandProbe commandProbe;
    private final String pathValue;
    private final boolean windows;

    /** 使用当前进程的 PATH 和操作系统信息创建环境检查服务。 */
    public LocalRuntimeReadinessService(
            CodeWeaveProperties properties,
            CommandProbe commandProbe
    ) {
        this(
                properties,
                commandProbe,
                System.getenv("PATH"),
                System.getProperty("os.name", "")
        );
    }

    /** 使用可控的 PATH 和操作系统信息创建便于测试的环境检查服务。 */
    LocalRuntimeReadinessService(
            CodeWeaveProperties properties,
            CommandProbe commandProbe,
            String pathValue,
            String operatingSystem
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.commandProbe = Objects.requireNonNull(commandProbe, "commandProbe");
        this.pathValue = pathValue;
        this.windows = Objects.requireNonNull(operatingSystem, "operatingSystem")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }

    /** 按固定顺序检查运行目录、Git、Java、Maven 和 Claude Code。 */
    @Override
    public RuntimeReadiness check() {
        List<RuntimeCheck> checks = new ArrayList<>();
        checks.add(checkRuntimeRoot());
        checks.add(checkPathTool(
                RuntimeComponent.GIT,
                windows ? List.of("git.exe") : List.of("git"),
                List.of("--version"),
                GIT_VERSION,
                MIN_GIT_VERSION,
                "Git",
                "请安装 Git 2.40+ 并确保其可执行文件位于 PATH 中。"
        ));
        checks.add(checkPathTool(
                RuntimeComponent.JAVA,
                windows ? List.of("java.exe") : List.of("java"),
                List.of("-version"),
                JAVA_VERSION,
                MIN_JAVA_VERSION,
                "Java",
                "请安装 JDK 21，并确保 java 可执行文件位于 PATH 中。"
        ));
        checks.add(checkPathTool(
                RuntimeComponent.MAVEN,
                windows ? List.of("mvn.cmd", "mvn.bat", "mvn.exe") : List.of("mvn"),
                List.of("--version"),
                MAVEN_VERSION,
                MIN_MAVEN_VERSION,
                "Maven",
                "请安装 Maven 3.9+ 并确保其可执行文件位于 PATH 中。"
        ));
        checks.add(checkClaudeCode());
        return RuntimeReadiness.from(Instant.now(), checks);
    }

    /** 检查平台运行目录是否存在、为目录且可写。 */
    private RuntimeCheck checkRuntimeRoot() {
        final Path runtimeRoot;
        try {
            runtimeRoot = properties.requireRuntimeRoot();
        } catch (RuntimeException exception) {
            return failed(
                    RuntimeComponent.RUNTIME_ROOT,
                    null,
                    null,
                    "平台运行目录配置无效。",
                    "请将 CODE_WEAVE_RUNTIME_ROOT 配置为本机绝对路径。"
            );
        }
        if (!Files.isDirectory(runtimeRoot)) {
            return failed(
                    RuntimeComponent.RUNTIME_ROOT,
                    null,
                    runtimeRoot.toString(),
                    "平台运行目录不存在或不是文件夹。",
                    "请创建该目录，并确认 Control Plane 进程具有访问权限。"
            );
        }
        if (!Files.isWritable(runtimeRoot)) {
            return failed(
                    RuntimeComponent.RUNTIME_ROOT,
                    null,
                    runtimeRoot.toString(),
                    "平台运行目录不可写。",
                    "请为当前用户授予该目录写入权限。"
            );
        }
        return passed(
                RuntimeComponent.RUNTIME_ROOT,
                null,
                runtimeRoot.toString(),
                "平台运行目录可用于保存 Worktree、事件和任务产物。"
        );
    }

    /** 从 PATH 解析工具并校验其版本是否满足最低要求。 */
    private RuntimeCheck checkPathTool(
            RuntimeComponent component,
            List<String> executableNames,
            List<String> arguments,
            Pattern versionPattern,
            SemanticVersion minimumVersion,
            String displayName,
            String remediation
    ) {
        Optional<Path> executable = resolveFromPath(executableNames);
        if (executable.isEmpty()) {
            return failed(
                    component,
                    null,
                    null,
                    displayName + " 可执行文件未找到。",
                    remediation
            );
        }
        List<String> command = new ArrayList<>();
        command.add(executable.get().toString());
        command.addAll(arguments);
        CommandProbeResult result = commandProbe.execute(command, PROBE_TIMEOUT);
        return versionCheck(
                component,
                executable.get(),
                result,
                versionPattern,
                minimumVersion,
                displayName,
                remediation
        );
    }

    /** 校验显式配置的 Claude Code 入口、版本和命令参数能力。 */
    private RuntimeCheck checkClaudeCode() {
        final Path executable;
        try {
            executable = properties.requireClaudeExecutable();
        } catch (RuntimeException exception) {
            return failed(
                    RuntimeComponent.CLAUDE_CODE,
                    null,
                    null,
                    "Claude Code 可执行文件配置无效。",
                    "请将 CODE_WEAVE_CLAUDE_EXECUTABLE 配置为 claude.cmd 的绝对路径。"
            );
        }
        if (!Files.isRegularFile(executable)) {
            return failed(
                    RuntimeComponent.CLAUDE_CODE,
                    null,
                    executable.toString(),
                    "Claude Code 可执行文件不存在。",
                    "请确认配置路径指向实际存在的 Claude Code 启动文件。"
            );
        }
        if (windows && !windowsLauncherSupported(executable)) {
            return failed(
                    RuntimeComponent.CLAUDE_CODE,
                    null,
                    executable.toString(),
                    "当前 Claude Code 启动入口不能由 Java 进程直接执行。",
                    "Windows 环境请配置 claude.cmd，而不是 claude.ps1。"
            );
        }

        CommandProbeResult versionResult = commandProbe.execute(
                List.of(executable.toString(), "--version"), PROBE_TIMEOUT
        );
        RuntimeCheck versionCheck = versionCheck(
                RuntimeComponent.CLAUDE_CODE,
                executable,
                versionResult,
                CLAUDE_VERSION,
                MIN_CLAUDE_VERSION,
                "Claude Code",
                "请安装 Claude Code 2.1+，并更新 CODE_WEAVE_CLAUDE_EXECUTABLE。"
        );
        if (versionCheck.status() == RuntimeCheckStatus.FAILED) {
            return versionCheck;
        }

        CommandProbeResult helpResult = commandProbe.execute(
                List.of(executable.toString(), "--help"), PROBE_TIMEOUT
        );
        if (!helpResult.successful()) {
            logProbeFailure(RuntimeComponent.CLAUDE_CODE, helpResult);
            return failed(
                    RuntimeComponent.CLAUDE_CODE,
                    versionCheck.version(),
                    executable.toString(),
                    "无法读取 Claude Code 命令能力。",
                    "请在终端执行 claude --help，确认 CLI 可以正常启动。"
            );
        }
        List<String> missingOptions = REQUIRED_CLAUDE_OPTIONS.stream()
                .filter(option -> !helpResult.output().contains(option))
                .toList();
        if (!missingOptions.isEmpty()) {
            LOGGER.warn("Claude Code 缺少必要参数，missing={}", missingOptions);
            return failed(
                    RuntimeComponent.CLAUDE_CODE,
                    versionCheck.version(),
                    executable.toString(),
                    "Claude Code 与当前 Adapter 命令协议不兼容。",
                    "请升级 Claude Code；缺少参数：" + String.join("、", missingOptions)
            );
        }
        return passed(
                RuntimeComponent.CLAUDE_CODE,
                versionCheck.version(),
                executable.toString(),
                "Claude Code 已就绪，命令协议与平台兼容。"
        );
    }

    /** 将命令结果转换为最低版本检查结论。 */
    private RuntimeCheck versionCheck(
            RuntimeComponent component,
            Path executable,
            CommandProbeResult result,
            Pattern versionPattern,
            SemanticVersion minimumVersion,
            String displayName,
            String remediation
    ) {
        if (!result.successful()) {
            logProbeFailure(component, result);
            return failed(
                    component,
                    null,
                    executable.toString(),
                    displayName + " 无法正常执行版本检查。",
                    remediation
            );
        }
        Optional<SemanticVersion> detected = parseVersion(result.output(), versionPattern);
        if (detected.isEmpty()) {
            return failed(
                    component,
                    null,
                    executable.toString(),
                    displayName + " 返回了无法识别的版本信息。",
                    remediation
            );
        }
        String version = detected.get().toString();
        if (detected.get().compareTo(minimumVersion) < 0) {
            return failed(
                    component,
                    version,
                    executable.toString(),
                    displayName + " 版本过低，当前版本为 " + version + "。",
                    remediation
            );
        }
        return passed(
                component,
                version,
                executable.toString(),
                displayName + " " + version + " 已就绪。"
        );
    }

    /** 从系统 PATH 的绝对目录中解析第一个匹配的可执行文件。 */
    private Optional<Path> resolveFromPath(List<String> executableNames) {
        if (pathValue == null || pathValue.isBlank()) {
            return Optional.empty();
        }
        for (String entry : pathValue.split(Pattern.quote(File.pathSeparator))) {
            String unquoted = unquote(entry);
            if (unquoted.isBlank()) {
                continue;
            }
            Path directory;
            try {
                directory = Path.of(unquoted);
            } catch (RuntimeException exception) {
                continue;
            }
            if (!directory.isAbsolute()) {
                continue;
            }
            for (String executableName : executableNames) {
                Path candidate = directory.resolve(executableName).toAbsolutePath().normalize();
                if (Files.isRegularFile(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    /** 从命令输出中解析三段式版本号。 */
    private Optional<SemanticVersion> parseVersion(String output, Pattern pattern) {
        Matcher matcher = pattern.matcher(output == null ? "" : output);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(new SemanticVersion(
                Integer.parseInt(matcher.group(1)),
                parseOptionalPart(matcher, 2),
                parseOptionalPart(matcher, 3)
        ));
    }

    /** 将可能缺失的版本号部分转换为零。 */
    private int parseOptionalPart(Matcher matcher, int group) {
        String value = matcher.group(group);
        return value == null ? 0 : Integer.parseInt(value);
    }

    /** 判断 Windows 上的启动文件是否可由 ProcessBuilder 直接使用。 */
    private boolean windowsLauncherSupported(Path executable) {
        String name = executable.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".cmd") || name.endsWith(".bat") || name.endsWith(".exe");
    }

    /** 去除 PATH 条目首尾可能存在的引号。 */
    private String unquote(String value) {
        String stripped = value.strip();
        if (stripped.length() >= 2 && stripped.startsWith("\"") && stripped.endsWith("\"")) {
            return stripped.substring(1, stripped.length() - 1);
        }
        return stripped;
    }

    /** 记录仅供服务端排障的探测失败诊断。 */
    private void logProbeFailure(RuntimeComponent component, CommandProbeResult result) {
        LOGGER.warn(
                "运行环境探测失败，component={}，started={}，timedOut={}，exitCode={}，diagnostic={}",
                component, result.started(), result.timedOut(), result.exitCode(), result.diagnostic()
        );
    }

    /** 创建通过状态的必要检查项。 */
    private RuntimeCheck passed(
            RuntimeComponent component,
            String version,
            String executable,
            String message
    ) {
        return new RuntimeCheck(
                component, RuntimeCheckStatus.PASSED, true,
                version, executable, message, null
        );
    }

    /** 创建失败状态的必要检查项。 */
    private RuntimeCheck failed(
            RuntimeComponent component,
            String version,
            String executable,
            String message,
            String remediation
    ) {
        return new RuntimeCheck(
                component, RuntimeCheckStatus.FAILED, true,
                version, executable, message, remediation
        );
    }

    /** 保存并比较三段式工具版本。 */
    private record SemanticVersion(int major, int minor, int patch)
            implements Comparable<SemanticVersion> {

        /** 按主版本、次版本和补丁版本依次比较。 */
        @Override
        public int compareTo(SemanticVersion other) {
            int majorComparison = Integer.compare(major, other.major);
            if (majorComparison != 0) {
                return majorComparison;
            }
            int minorComparison = Integer.compare(minor, other.minor);
            return minorComparison != 0 ? minorComparison : Integer.compare(patch, other.patch);
        }

        /** 输出标准三段式版本号。 */
        @Override
        public String toString() {
            return major + "." + minor + "." + patch;
        }
    }
}
