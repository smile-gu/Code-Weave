package com.codingreview.controlplane.verification.maven;

import com.codingreview.controlplane.observability.run.RunEventJournal;
import com.codingreview.controlplane.observability.run.RunEventType;
import com.codingreview.controlplane.verification.maven.model.MavenTestSummary;
import com.codingreview.controlplane.verification.maven.model.MavenVerificationRequest;
import com.codingreview.controlplane.verification.maven.model.MavenVerificationResult;
import com.codingreview.controlplane.verification.maven.model.MavenVerificationStatus;
import com.codingreview.controlplane.verification.maven.report.MavenReportException;
import com.codingreview.controlplane.verification.maven.report.SurefireReportParser;
import com.codingreview.controlplane.verification.maven.runtime.MavenProcessResult;
import com.codingreview.controlplane.verification.maven.runtime.MavenProcessRunner;
import com.codingreview.controlplane.verification.maven.runtime.MavenProcessSpec;
import com.codingreview.controlplane.verification.maven.runtime.MavenProcessStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** 编排 Maven 进程证据、Surefire 报告和最终验证结论。 */
public final class MavenVerifierImpl implements MavenVerifier {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration DEFAULT_CANCELLATION_GRACE = Duration.ofSeconds(2);
    private static final long DEFAULT_MAX_LOG_BYTES = 100L * 1024 * 1024;
    private static final String SAFE_RUN_ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}";

    private final Path runtimeRoot;
    private final RunEventJournal eventJournal;
    private final ObjectMapper objectMapper;
    private final SurefireReportParser reportParser;
    private final MavenProcessRunner processRunner;
    private final List<String> commandPrefix;
    private final Duration timeout;
    private final Duration cancellationGracePeriod;
    private final long maxLogBytes;

    public MavenVerifierImpl(Path runtimeRoot, RunEventJournal eventJournal) {
        this(
                runtimeRoot,
                eventJournal,
                JsonMapper.builder().findAndAddModules().build(),
                new SurefireReportParser(),
                List.of(resolveSystemMavenExecutable()),
                DEFAULT_TIMEOUT,
                DEFAULT_CANCELLATION_GRACE,
                DEFAULT_MAX_LOG_BYTES
        );
    }

    MavenVerifierImpl(
            Path runtimeRoot,
            RunEventJournal eventJournal,
            ObjectMapper objectMapper,
            SurefireReportParser reportParser,
            List<String> commandPrefix,
            Duration timeout,
            Duration cancellationGracePeriod,
            long maxLogBytes
    ) {
        if (runtimeRoot == null || !runtimeRoot.isAbsolute()) {
            throw new IllegalArgumentException("runtimeRoot must be absolute");
        }
        this.runtimeRoot = runtimeRoot.normalize();
        this.eventJournal = Objects.requireNonNull(eventJournal, "eventJournal");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        this.reportParser = Objects.requireNonNull(reportParser, "reportParser");
        this.processRunner = new MavenProcessRunner(this.objectMapper);
        this.commandPrefix = List.copyOf(commandPrefix);
        if (this.commandPrefix.isEmpty()) {
            throw new IllegalArgumentException("commandPrefix must not be empty");
        }
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.cancellationGracePeriod = Objects.requireNonNull(
                cancellationGracePeriod, "cancellationGracePeriod"
        );
        if (timeout.isZero() || timeout.isNegative() || cancellationGracePeriod.isNegative()) {
            throw new IllegalArgumentException("Maven durations are invalid");
        }
        if (maxLogBytes < 1) {
            throw new IllegalArgumentException("maxLogBytes must be positive");
        }
        this.maxLogBytes = maxLogBytes;
    }

    /** 执行 Maven 验证并汇总进程、日志和测试报告证据。 */
    @Override
    public MavenVerificationResult verify(MavenVerificationRequest request) {
        Objects.requireNonNull(request, "request");
        String runId = request.worktree().runId();
        validateRunId(runId);
        Path worktree = request.worktree().worktreePath().normalize();
        List<String> command = command();
        Instant validationStartedAt = Instant.now();

        String invalidWorkspace = validateWorkspace(worktree);
        if (invalidWorkspace != null) {
            return new MavenVerificationResult(
                    MavenVerificationStatus.START_FAILED,
                    null,
                    command,
                    validationStartedAt,
                    Instant.now(),
                    null,
                    null,
                    null,
                    "无法开始 Maven 验证，请确认任务目录是包含 pom.xml 的有效 Worktree。",
                    invalidWorkspace
            );
        }

        Path artifactDirectory = artifactDirectory(runId, request.attempt());
        MavenProcessSpec spec = new MavenProcessSpec(
                command,
                worktree,
                artifactDirectory,
                runtimeRoot,
                timeout,
                cancellationGracePeriod,
                maxLogBytes
        );
        MavenProcessResult process = processRunner.run(spec,
                (type, payload) -> appendEvent(request, type, payload));

        if (process.status() != MavenProcessStatus.COMPLETED) {
            return processFailure(command, process);
        }

        MavenTestSummary summary;
        try {
            summary = reportParser.parse(worktree);
        } catch (MavenReportException exception) {
            appendNotice(request, "MAVEN_REPORT_INVALID",
                    "测试报告无法可靠解析，本次验证不能判定为通过。", exception.getMessage());
            return result(
                    MavenVerificationStatus.REPORT_INVALID,
                    command,
                    process,
                    null,
                    "Surefire 测试报告无法可靠解析，本次验证已失败。",
                    exception.getMessage()
            );
        }

        String summaryPath;
        try {
            summaryPath = writeSummary(artifactDirectory, summary);
        } catch (IOException | RuntimeException exception) {
            return result(
                    MavenVerificationStatus.LOG_CAPTURE_FAILED,
                    command,
                    process,
                    summary,
                    "Maven 验证结果无法可靠保存，本次验证已失败。",
                    "Unable to persist Surefire summary: " + exception.getMessage()
            );
        }

        appendEvent(request, RunEventType.ARTIFACT_RECORDED,
                objectMapper.createObjectNode()
                        .put("kind", "surefire-summary")
                        .put("path", summaryPath));

        MavenVerificationStatus status = classify(process.exitCode(), summary);
        if (!summary.reportsFound() && status == MavenVerificationStatus.PASSED) {
            appendNotice(request, "NO_SUREFIRE_REPORTS",
                    "Maven 执行成功，但没有发现 Surefire 测试报告。", null);
        }
        appendAnalysisCompleted(request, status, summary);
        return result(
                status,
                command,
                process,
                summary,
                userMessage(status, summary),
                diagnostic(status, process.exitCode(), summary)
        );
    }

    /** 构造平台固定的 Maven clean test 命令。 */
    private List<String> command() {
        List<String> command = new ArrayList<>(commandPrefix);
        command.add("-B");
        command.add("-ntp");
        command.add("-Dstyle.color=never");
        command.add("-Dfile.encoding=UTF-8");
        command.add("clean");
        command.add("test");
        return List.copyOf(command);
    }

    /** 校验 Worktree 是否具备 Maven 项目的基本运行条件。 */
    private String validateWorkspace(Path worktree) {
        if (!worktree.isAbsolute()) {
            return "Worktree path is not absolute: " + worktree;
        }
        if (!Files.isDirectory(worktree)) {
            return "Worktree directory does not exist: " + worktree;
        }
        if (!Files.isRegularFile(worktree.resolve("pom.xml"))) {
            return "pom.xml does not exist at Worktree root: " + worktree;
        }
        return null;
    }

    /** 将 Maven 进程层失败转换为业务验证结果。 */
    private MavenVerificationResult processFailure(List<String> command, MavenProcessResult process) {
        MavenVerificationStatus status = switch (process.status()) {
            case START_FAILED -> MavenVerificationStatus.START_FAILED;
            case TIMED_OUT -> MavenVerificationStatus.TIMED_OUT;
            case CANCELLED -> MavenVerificationStatus.CANCELLED;
            case LOG_LIMIT_EXCEEDED -> MavenVerificationStatus.LOG_LIMIT_EXCEEDED;
            case LOG_CAPTURE_FAILED -> MavenVerificationStatus.LOG_CAPTURE_FAILED;
            case COMPLETED -> throw new IllegalArgumentException("completed process is not a failure");
        };
        return result(status, command, process, null, userMessage(status, null), process.diagnostic());
    }

    /** 根据退出码和测试报告区分通过、构建失败与测试失败。 */
    private MavenVerificationStatus classify(Integer exitCode, MavenTestSummary summary) {
        if (Integer.valueOf(0).equals(exitCode)) {
            return MavenVerificationStatus.PASSED;
        }
        return summary.hasTestFailures()
                ? MavenVerificationStatus.TEST_FAILED
                : MavenVerificationStatus.BUILD_FAILED;
    }

    /** 生成面向用户的 Maven 验证结论。 */
    private String userMessage(MavenVerificationStatus status, MavenTestSummary summary) {
        return switch (status) {
            case PASSED -> summary != null && !summary.reportsFound()
                    ? "Maven 编译执行成功，但项目没有生成 Surefire 测试报告。"
                    : "Maven 编译和测试已通过。";
            case BUILD_FAILED -> "Maven 构建失败，请查看原始日志定位编译、依赖或插件问题。";
            case TEST_FAILED -> "Maven 测试未通过，请查看失败用例和原始日志。";
            case START_FAILED -> "Maven 无法启动，请检查 Maven 安装和任务目录。";
            case TIMED_OUT -> "Maven 执行超过时间限制，进程已被终止。";
            case CANCELLED -> "Maven 验证已取消，相关进程已被终止。";
            case LOG_LIMIT_EXCEEDED -> "Maven 日志超过 100 MB 安全上限，验证已被终止。";
            case LOG_CAPTURE_FAILED -> "Maven 日志或验证产物无法可靠保存，验证已被终止。";
            case REPORT_INVALID -> "Surefire 测试报告无法可靠解析，本次验证已失败。";
        };
    }

    /** 生成便于排查的 Maven 验证诊断摘要。 */
    private String diagnostic(
            MavenVerificationStatus status,
            Integer exitCode,
            MavenTestSummary summary
    ) {
        return "status=" + status
                + ", exitCode=" + exitCode
                + ", reports=" + summary.reportFiles()
                + ", tests=" + summary.tests()
                + ", failures=" + summary.failures()
                + ", errors=" + summary.errors();
    }

    /** 合并进程证据与报告结论生成验证结果。 */
    private MavenVerificationResult result(
            MavenVerificationStatus status,
            List<String> command,
            MavenProcessResult process,
            MavenTestSummary summary,
            String userMessage,
            String diagnostic
    ) {
        return new MavenVerificationResult(
                status,
                process.exitCode(),
                command,
                process.startedAt(),
                process.endedAt(),
                process.stdoutLog(),
                process.stderrLog(),
                summary,
                userMessage,
                diagnostic
        );
    }

    /** 记录 Maven 测试报告分析完成事件。 */
    private void appendAnalysisCompleted(
            MavenVerificationRequest request,
            MavenVerificationStatus status,
            MavenTestSummary summary
    ) {
        ObjectNode payload = objectMapper.createObjectNode()
                .put("status", status.name())
                .put("reportFiles", summary.reportFiles())
                .put("tests", summary.tests())
                .put("passed", summary.passed())
                .put("failures", summary.failures())
                .put("errors", summary.errors())
                .put("skipped", summary.skipped())
                .put("durationSeconds", summary.durationSeconds())
                .put("failureDetailsTruncated", summary.failureDetailsTruncated());
        appendEvent(request, RunEventType.ANALYSIS_COMPLETED, payload);
    }

    /** 记录需要用户关注但不属于进程输出的提示事件。 */
    private void appendNotice(
            MavenVerificationRequest request,
            String code,
            String message,
            String diagnostic
    ) {
        ObjectNode payload = objectMapper.createObjectNode()
                .put("code", code)
                .put("message", message);
        if (diagnostic == null || diagnostic.isBlank()) {
            payload.putNull("diagnostic");
        } else {
            payload.put("diagnostic", diagnostic);
        }
        appendEvent(request, RunEventType.RUN_NOTICE, payload);
    }

    /** 将 Maven 阶段事件写入当前 Run 的事件日志。 */
    private void appendEvent(
            MavenVerificationRequest request,
            RunEventType type,
            ObjectNode payload
    ) {
        eventJournal.append(
                request.worktree().runId(),
                "maven",
                request.attempt(),
                type,
                payload
        );
    }

    /** 将 Surefire 汇总结果持久化为 JSON 证据。 */
    private String writeSummary(Path artifactDirectory, MavenTestSummary summary) throws IOException {
        Path summaryFile = artifactDirectory.resolve("surefire-summary.json").normalize();
        byte[] json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(summary);
        ByteBuffer buffer = ByteBuffer.allocate(json.length + 1);
        buffer.put(json).put((byte) '\n').flip();
        try (FileChannel channel = FileChannel.open(summaryFile,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        return relativePath(summaryFile);
    }

    /** 计算本次 Maven 尝试的安全产物目录。 */
    private Path artifactDirectory(String runId, int attempt) {
        Path runsRoot = runtimeRoot.resolve("runs").normalize();
        Path directory = runsRoot.resolve(runId)
                .resolve("artifacts/maven/attempt-" + attempt)
                .normalize();
        if (!directory.startsWith(runsRoot)) {
            throw new IllegalArgumentException("Maven artifact directory resolves outside runtime root");
        }
        return directory;
    }

    /** 将 Maven 产物路径转换为运行时根目录内的相对路径。 */
    private String relativePath(Path file) {
        Path normalized = file.normalize();
        if (!normalized.startsWith(runtimeRoot)) {
            throw new IllegalArgumentException("Maven artifact resolves outside runtime root");
        }
        return runtimeRoot.relativize(normalized).toString().replace('\\', '/');
    }

    /** 校验 Run 标识可安全用于产物目录。 */
    private void validateRunId(String runId) {
        if (runId == null || !runId.matches(SAFE_RUN_ID_PATTERN)) {
            throw new IllegalArgumentException("runId contains unsupported characters");
        }
    }

    /** 从系统 PATH 中解析 Maven 可执行文件的绝对路径。 */
    private static String resolveSystemMavenExecutable() {
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
        List<String> executableNames = windows
                ? List.of("mvn.cmd", "mvn.bat", "mvn.exe")
                : List.of("mvn");
        String pathValue = System.getenv("PATH");
        if (pathValue == null || pathValue.isBlank()) {
            throw new IllegalStateException("PATH is empty; unable to locate system Maven");
        }

        return Arrays.stream(pathValue.split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .map(MavenVerifierImpl::unquotePathEntry)
                .filter(entry -> !entry.isBlank())
                .map(Path::of)
                .filter(Path::isAbsolute)
                .flatMap(directory -> executableNames.stream().map(directory::resolve))
                .filter(Files::isRegularFile)
                .map(path -> path.toAbsolutePath().normalize().toString())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Unable to locate system Maven in absolute PATH entries"
                ));
    }

    /** 去除 PATH 条目首尾可能存在的引号。 */
    private static String unquotePathEntry(String value) {
        String stripped = value.strip();
        if (stripped.length() >= 2 && stripped.startsWith("\"") && stripped.endsWith("\"")) {
            return stripped.substring(1, stripped.length() - 1);
        }
        return stripped;
    }
}
