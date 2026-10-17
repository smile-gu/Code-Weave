package com.codingreview.controlplane.workspace.change;

import com.codingreview.controlplane.workspace.change.error.GitChangeException;
import com.codingreview.controlplane.workspace.change.error.GitChangeFailureType;
import com.codingreview.controlplane.workspace.change.model.GitChangeSet;
import com.codingreview.controlplane.workspace.change.model.GitChangeType;
import com.codingreview.controlplane.workspace.change.model.GitChangedFile;
import com.codingreview.controlplane.workspace.model.WorkspaceHandle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 基于系统 Git CLI 生成结构化文件列表和统一 Diff。 */
public final class GitChangeCollectorImpl implements GitChangeCollector {

    public static final int DEFAULT_MAX_DIFF_BYTES = 2 * 1024 * 1024;
    private static final int METADATA_OUTPUT_LIMIT = 4 * 1024 * 1024;
    private static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofSeconds(30);

    private final String gitExecutable;
    private final Duration commandTimeout;
    private final int maxDiffBytes;
    private final GitChangeParser parser;
    private final long deadlineNanos;

    public GitChangeCollectorImpl() {
        this("git", DEFAULT_COMMAND_TIMEOUT, DEFAULT_MAX_DIFF_BYTES);
    }

    public GitChangeCollectorImpl(String gitExecutable, Duration commandTimeout, int maxDiffBytes) {
        this(gitExecutable, commandTimeout, maxDiffBytes, Long.MAX_VALUE);
    }

    public GitChangeCollectorImpl(
            String gitExecutable, Duration commandTimeout, int maxDiffBytes, long deadlineNanos) {
        if (gitExecutable == null || gitExecutable.isBlank()) {
            throw new IllegalArgumentException("gitExecutable must not be blank");
        }
        Objects.requireNonNull(commandTimeout, "commandTimeout");
        if (commandTimeout.isZero() || commandTimeout.isNegative()) {
            throw new IllegalArgumentException("commandTimeout must be positive");
        }
        if (maxDiffBytes <= 0 || maxDiffBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "maxDiffBytes must be between 1 and Integer.MAX_VALUE - 1");
        }
        this.gitExecutable = gitExecutable;
        this.commandTimeout = commandTimeout;
        this.maxDiffBytes = maxDiffBytes;
        this.parser = new GitChangeParser();
        this.deadlineNanos = deadlineNanos;
    }

    /** 采集并合并已跟踪文件与未跟踪文件的完整 Diff。 */
    @Override
    public GitChangeSet collect(WorkspaceHandle handle) {
        Path workspaceRoot = validateWorkspace(handle);
        validateBaseCommit(workspaceRoot, handle.baselineCommit());

        List<String> nameStatusArguments =
                List.of(
                        "diff",
                        "--name-status",
                        "-z",
                        "--find-renames",
                        handle.baselineCommit(),
                        "--");
        CommandResult nameStatus =
                requireSuccess(
                        runGit(workspaceRoot, nameStatusArguments, METADATA_OUTPUT_LIMIT),
                        "无法读取任务产生的文件列表。",
                        GitChangeFailureType.COLLECT_FAILED);

        CommandResult numstat =
                requireSuccess(
                        runGit(
                                workspaceRoot,
                                List.of(
                                        "diff",
                                        "--numstat",
                                        "-z",
                                        "--find-renames",
                                        handle.baselineCommit(),
                                        "--"),
                                METADATA_OUTPUT_LIMIT),
                        "无法识别变更中的二进制文件。",
                        GitChangeFailureType.COLLECT_FAILED);
        Set<String> binaryPaths = parser.parseBinaryPaths(numstat.output());
        CommandResult rawDiff =
                requireSuccess(
                        runGit(
                                workspaceRoot,
                                List.of(
                                        "diff",
                                        "--raw",
                                        "--no-abbrev",
                                        "-z",
                                        "--find-renames",
                                        handle.baselineCommit(),
                                        "--"),
                                METADATA_OUTPUT_LIMIT),
                        "无法读取变更文件的 Git mode。",
                        GitChangeFailureType.COLLECT_FAILED);
        Map<String, String> finalModes = parser.parseFinalModes(rawDiff.output());
        List<GitChangedFile> changedFiles =
                parser.parseTrackedFiles(nameStatus.output(), binaryPaths, finalModes);

        CommandResult untracked =
                requireSuccess(
                        runGit(
                                workspaceRoot,
                                List.of("ls-files", "--others", "--exclude-standard", "-z"),
                                METADATA_OUTPUT_LIMIT),
                        "无法读取任务新建的文件。",
                        GitChangeFailureType.COLLECT_FAILED);
        List<String> untrackedPaths = parser.splitNullTerminated(untracked.output());
        boolean coreFileMode = readCoreFileMode(workspaceRoot);

        ByteArrayOutputStream combinedDiff = new ByteArrayOutputStream();
        CommandResult trackedPatch =
                runGit(
                        workspaceRoot,
                        List.of(
                                "diff",
                                "--no-ext-diff",
                                "--find-renames",
                                handle.baselineCommit(),
                                "--"),
                        maxDiffBytes + 1);
        requirePatchWithinLimit(trackedPatch);
        requireSuccess(trackedPatch, "无法生成已跟踪文件的 Diff。", GitChangeFailureType.COLLECT_FAILED);
        appendPatch(combinedDiff, trackedPatch.output());

        String nullDevice =
                System.getProperty("os.name").toLowerCase().contains("win") ? "NUL" : "/dev/null";
        for (String path : untrackedPaths) {
            int remaining = maxDiffBytes - combinedDiff.size();
            if (remaining <= 0) {
                throw diffTooLarge();
            }
            CommandResult newFilePatch =
                    runGit(
                            workspaceRoot,
                            List.of("diff", "--no-index", "--", nullDevice, path),
                            remaining + 1);
            if (newFilePatch.exitCode() != 0 && newFilePatch.exitCode() != 1) {
                throw failure(
                        GitChangeFailureType.COLLECT_FAILED,
                        "无法读取任务新建文件的 Diff。",
                        newFilePatch.outputAsText());
            }
            requirePatchWithinLimit(newFilePatch);
            boolean binary = parser.containsBinaryMarker(newFilePatch.output());
            changedFiles.add(
                    new GitChangedFile(
                            path,
                            null,
                            GitChangeType.ADDED,
                            binary,
                            untrackedGitMode(workspaceRoot, path, coreFileMode)));
            appendPatch(combinedDiff, newFilePatch.output());
        }

        changedFiles.sort(Comparator.comparing(GitChangedFile::path));
        byte[] diffBytes = combinedDiff.toByteArray();
        return new GitChangeSet(
                handle.baselineCommit(),
                workspaceRoot,
                changedFiles,
                new String(diffBytes, StandardCharsets.UTF_8),
                diffBytes.length,
                diffBytes);
    }

    /** 校验工作区路径和 Git 顶层目录是否一致。 */
    private Path validateWorkspace(WorkspaceHandle handle) {
        Objects.requireNonNull(handle, "handle");
        Path workspaceRoot = handle.workspaceRoot();
        if (!Files.isDirectory(workspaceRoot)) {
            throw failure(
                    GitChangeFailureType.INVALID_WORKSPACE,
                    "任务工作区不存在，无法读取代码变更。",
                    workspaceRoot.toString());
        }
        CommandResult topLevel =
                runGit(
                        workspaceRoot,
                        List.of("rev-parse", "--show-toplevel"),
                        METADATA_OUTPUT_LIMIT);
        if (!topLevel.success()) {
            throw failure(
                    GitChangeFailureType.INVALID_WORKSPACE,
                    "任务工作区不是有效的 Git 工作目录。",
                    topLevel.outputAsText());
        }
        try {
            Path actualTopLevel = Path.of(topLevel.outputAsText().trim()).toRealPath();
            Path expectedTopLevel = workspaceRoot.toRealPath();
            if (!actualTopLevel.equals(expectedTopLevel)) {
                throw failure(
                        GitChangeFailureType.INVALID_WORKSPACE,
                        "任务工作区与 Git 顶层目录不一致，已停止采集。",
                        actualTopLevel.toString());
            }
            return expectedTopLevel;
        } catch (IOException exception) {
            throw failure(
                    GitChangeFailureType.INVALID_WORKSPACE,
                    "无法解析任务工作区的真实路径。",
                    exception.getMessage());
        }
    }

    /** 读取 Git 对工作区可执行位的跟踪策略，未配置时使用 Git 默认值。 */
    private boolean readCoreFileMode(Path workspaceRoot) {
        CommandResult result =
                runGit(
                        workspaceRoot,
                        List.of("config", "--type=bool", "--get", "core.filemode"),
                        METADATA_OUTPUT_LIMIT);
        if (result.exitCode() == 1 && result.output().length == 0) {
            return true;
        }
        requireSuccess(result, "无法读取 Git 文件模式配置。", GitChangeFailureType.COLLECT_FAILED);
        String value = result.outputAsText().trim();
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        throw failure(GitChangeFailureType.COLLECT_FAILED, "Git 文件模式配置无法识别。", value);
    }

    /** 推导未跟踪文件加入 Git 后会使用的最终 mode。 */
    private String untrackedGitMode(Path workspaceRoot, String relativePath, boolean coreFileMode) {
        Path path = workspaceRoot.resolve(relativePath).normalize();
        if (Files.isSymbolicLink(path)) {
            return "120000";
        }
        if (!coreFileMode) {
            return "100644";
        }
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            boolean executable =
                    permissions.contains(PosixFilePermission.OWNER_EXECUTE)
                            || permissions.contains(PosixFilePermission.GROUP_EXECUTE)
                            || permissions.contains(PosixFilePermission.OTHERS_EXECUTE);
            return executable ? "100755" : "100644";
        } catch (UnsupportedOperationException exception) {
            return "100644";
        } catch (IOException exception) {
            throw failure(
                    GitChangeFailureType.COLLECT_FAILED,
                    "无法读取未跟踪文件的 Git mode。",
                    relativePath + ": " + exception.getMessage());
        }
    }

    /** 确认用于比较的基准提交在工作区中仍然可用。 */
    private void validateBaseCommit(Path workspaceRoot, String baseCommit) {
        CommandResult result =
                runGit(
                        workspaceRoot,
                        List.of("cat-file", "-e", baseCommit + "^{commit}"),
                        METADATA_OUTPUT_LIMIT);
        if (!result.success()) {
            throw failure(
                    GitChangeFailureType.BASE_COMMIT_UNAVAILABLE,
                    "任务基准提交已不可用，无法可靠计算代码变更。",
                    result.outputAsText());
        }
    }

    /** 在总大小限制内追加一段补丁内容。 */
    private void appendPatch(ByteArrayOutputStream target, byte[] patch) {
        if (patch.length == 0) {
            return;
        }
        if (target.size() > 0) {
            target.write('\n');
        }
        if ((long) target.size() + patch.length > maxDiffBytes) {
            throw diffTooLarge();
        }
        target.writeBytes(patch);
    }

    /** 拒绝超过平台展示上限的补丁输出。 */
    private void requirePatchWithinLimit(CommandResult result) {
        if (result.truncated() || result.output().length > maxDiffBytes) {
            throw diffTooLarge();
        }
    }

    /** 校验 Git 命令是否成功且输出完整。 */
    private CommandResult requireSuccess(
            CommandResult result, String userMessage, GitChangeFailureType failureType) {
        if (!result.success() || result.truncated()) {
            throw failure(failureType, userMessage, result.outputAsText());
        }
        return result;
    }

    /** 创建表示 Diff 超过大小上限的异常。 */
    private GitChangeException diffTooLarge() {
        return failure(
                GitChangeFailureType.DIFF_TOO_LARGE,
                "本次代码变更超过平台展示上限，请缩小任务范围后重试。",
                "maxDiffBytes=" + maxDiffBytes);
    }

    /** 执行一次带输出上限和超时限制的 Git 命令。 */
    private CommandResult runGit(Path workingDirectory, List<String> arguments, int outputLimit) {
        ProcessBuilder processBuilder =
                com.codingreview.controlplane.workspace.git.ReadOnlyGit.process(
                        gitExecutable, workingDirectory, arguments);
        List<String> command = processBuilder.command();
        try {
            Process process = processBuilder.start();
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<LimitedOutput> stdoutFuture =
                        executor.submit(
                                () -> readProcessOutput(process.getInputStream(), outputLimit));
                Future<LimitedOutput> stderrFuture =
                        executor.submit(
                                () ->
                                        readProcessOutput(
                                                process.getErrorStream(), METADATA_OUTPUT_LIMIT));
                long remaining =
                        Math.min(
                                commandTimeout.toNanos(),
                                deadlineNanos == Long.MAX_VALUE
                                        ? commandTimeout.toNanos()
                                        : deadlineNanos - System.nanoTime());
                if (remaining <= 0 || !process.waitFor(remaining, TimeUnit.NANOSECONDS)) {
                    process.destroyForcibly();
                    process.waitFor();
                    throw failure(
                            GitChangeFailureType.GIT_COMMAND_FAILED,
                            "Git 变更采集命令执行超时。",
                            String.join(" ", command));
                }
                LimitedOutput stdout = awaitOutput(stdoutFuture);
                LimitedOutput stderr = awaitOutput(stderrFuture);
                return new CommandResult(
                        process.exitValue(),
                        stdout.bytes(),
                        stdout.truncated(),
                        stderr.bytes(),
                        stderr.truncated());
            }
        } catch (IOException exception) {
            throw failure(
                    GitChangeFailureType.GIT_COMMAND_FAILED,
                    "无法执行 Git，请确认 Git 已安装并加入 PATH。",
                    exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure(
                    GitChangeFailureType.GIT_COMMAND_FAILED,
                    "Git 变更采集被中断。",
                    exception.getMessage());
        }
    }

    /** 读取并关闭 Git 进程的单个输出流。 */
    private LimitedOutput readProcessOutput(InputStream input, int limit) throws IOException {
        try (input) {
            return readLimited(input, limit);
        }
    }

    /** 获取异步读取结果并还原底层输入输出异常。 */
    private LimitedOutput awaitOutput(Future<LimitedOutput> future)
            throws IOException, InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("无法读取 Git 命令输出。", exception.getCause());
        }
    }

    /** 在指定字节上限内读取命令输出。 */
    private LimitedOutput readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        boolean truncated = false;
        int read;
        while ((read = input.read(buffer)) != -1) {
            int remaining = limit - output.size();
            if (remaining > 0) {
                output.write(buffer, 0, Math.min(remaining, read));
            }
            if (read > remaining) {
                truncated = true;
            }
        }
        return new LimitedOutput(output.toByteArray(), truncated);
    }

    /** 创建包含稳定类型和用户提示的 Git 变更异常。 */
    private GitChangeException failure(
            GitChangeFailureType type, String userMessage, String diagnostic) {
        return new GitChangeException(type, userMessage, diagnostic);
    }

    private record LimitedOutput(byte[] bytes, boolean truncated) {}

    private record CommandResult(
            int exitCode,
            byte[] output,
            boolean truncated,
            byte[] diagnostic,
            boolean diagnosticTruncated) {
        /** 判断 Git 命令是否正常退出。 */
        private boolean success() {
            return exitCode == 0;
        }

        /** 优先将 Git 错误流转换为诊断文本，避免污染业务输出。 */
        private String outputAsText() {
            String errorText = new String(diagnostic, StandardCharsets.UTF_8).trim();
            if (!errorText.isEmpty()) {
                return diagnosticTruncated ? errorText + "\n[stderr truncated]" : errorText;
            }
            return new String(output, StandardCharsets.UTF_8);
        }
    }
}
