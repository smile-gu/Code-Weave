package com.codingreview.controlplane.workspace.change;

import com.codingreview.controlplane.workspace.change.error.GitChangeException;
import com.codingreview.controlplane.workspace.change.error.GitChangeFailureType;
import com.codingreview.controlplane.workspace.change.model.GitChangeSet;
import com.codingreview.controlplane.workspace.change.model.GitChangeType;
import com.codingreview.controlplane.workspace.change.model.GitChangedFile;
import com.codingreview.controlplane.workspace.worktree.model.WorktreeHandle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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

    public GitChangeCollectorImpl() {
        this("git", DEFAULT_COMMAND_TIMEOUT, DEFAULT_MAX_DIFF_BYTES);
    }

    public GitChangeCollectorImpl(String gitExecutable, Duration commandTimeout, int maxDiffBytes) {
        if (gitExecutable == null || gitExecutable.isBlank()) {
            throw new IllegalArgumentException("gitExecutable must not be blank");
        }
        Objects.requireNonNull(commandTimeout, "commandTimeout");
        if (commandTimeout.isZero() || commandTimeout.isNegative()) {
            throw new IllegalArgumentException("commandTimeout must be positive");
        }
        if (maxDiffBytes <= 0 || maxDiffBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maxDiffBytes must be between 1 and Integer.MAX_VALUE - 1");
        }
        this.gitExecutable = gitExecutable;
        this.commandTimeout = commandTimeout;
        this.maxDiffBytes = maxDiffBytes;
        this.parser = new GitChangeParser();
    }

    /** 采集并合并已跟踪文件与未跟踪文件的完整 Diff。 */
    @Override
    public GitChangeSet collect(WorktreeHandle handle) {
        Path worktree = validateWorktree(handle);
        validateBaseCommit(worktree, handle.resolvedCommit());

        List<String> nameStatusArguments = List.of(
                "diff", "--name-status", "-z", "--find-renames", handle.resolvedCommit(), "--");
        CommandResult nameStatus = requireSuccess(runGit(worktree, nameStatusArguments, METADATA_OUTPUT_LIMIT),
                "无法读取任务产生的文件列表。", GitChangeFailureType.COLLECT_FAILED);

        CommandResult numstat = requireSuccess(runGit(worktree, List.of(
                        "diff", "--numstat", "-z", "--find-renames", handle.resolvedCommit(), "--"),
                METADATA_OUTPUT_LIMIT), "无法识别变更中的二进制文件。", GitChangeFailureType.COLLECT_FAILED);
        Set<String> binaryPaths = parser.parseBinaryPaths(numstat.output());
        List<GitChangedFile> changedFiles = parser.parseTrackedFiles(nameStatus.output(), binaryPaths);

        CommandResult untracked = requireSuccess(runGit(worktree,
                        List.of("ls-files", "--others", "--exclude-standard", "-z"), METADATA_OUTPUT_LIMIT),
                "无法读取任务新建的文件。", GitChangeFailureType.COLLECT_FAILED);
        List<String> untrackedPaths = parser.splitNullTerminated(untracked.output());

        ByteArrayOutputStream combinedDiff = new ByteArrayOutputStream();
        CommandResult trackedPatch = runGit(worktree, List.of(
                "diff", "--no-ext-diff", "--find-renames", handle.resolvedCommit(), "--"),
                maxDiffBytes + 1);
        requirePatchWithinLimit(trackedPatch);
        requireSuccess(trackedPatch, "无法生成已跟踪文件的 Diff。", GitChangeFailureType.COLLECT_FAILED);
        appendPatch(combinedDiff, trackedPatch.output());

        String nullDevice = System.getProperty("os.name").toLowerCase().contains("win") ? "NUL" : "/dev/null";
        for (String path : untrackedPaths) {
            int remaining = maxDiffBytes - combinedDiff.size();
            if (remaining <= 0) {
                throw diffTooLarge();
            }
            CommandResult newFilePatch = runGit(worktree,
                    List.of("diff", "--no-index", "--", nullDevice, path), remaining + 1);
            if (newFilePatch.exitCode() != 0 && newFilePatch.exitCode() != 1) {
                throw failure(GitChangeFailureType.COLLECT_FAILED,
                        "无法读取任务新建文件的 Diff。", newFilePatch.outputAsText());
            }
            requirePatchWithinLimit(newFilePatch);
            boolean binary = parser.containsBinaryMarker(newFilePatch.output());
            changedFiles.add(new GitChangedFile(path, null, GitChangeType.ADDED, binary));
            appendPatch(combinedDiff, newFilePatch.output());
        }

        changedFiles.sort(Comparator.comparing(GitChangedFile::path));
        byte[] diffBytes = combinedDiff.toByteArray();
        return new GitChangeSet(handle.resolvedCommit(), worktree, changedFiles,
                new String(diffBytes, StandardCharsets.UTF_8), diffBytes.length);
    }

    /** 校验 Worktree 路径和 Git 顶层目录是否一致。 */
    private Path validateWorktree(WorktreeHandle handle) {
        Objects.requireNonNull(handle, "handle");
        Path worktree = handle.worktreePath();
        if (!worktree.isAbsolute() || !Files.isDirectory(worktree)) {
            throw failure(GitChangeFailureType.INVALID_WORKTREE,
                    "任务隔离目录不存在，无法读取代码变更。", worktree.toString());
        }
        CommandResult topLevel = runGit(worktree, List.of("rev-parse", "--show-toplevel"), METADATA_OUTPUT_LIMIT);
        if (!topLevel.success()) {
            throw failure(GitChangeFailureType.INVALID_WORKTREE,
                    "任务隔离目录不是有效的 Git Worktree。", topLevel.outputAsText());
        }
        try {
            Path actualTopLevel = Path.of(topLevel.outputAsText().trim()).toRealPath();
            Path expectedTopLevel = worktree.toRealPath();
            if (!actualTopLevel.equals(expectedTopLevel)) {
                throw failure(GitChangeFailureType.INVALID_WORKTREE,
                        "任务隔离目录与 Git 工作目录不一致，已停止采集。", actualTopLevel.toString());
            }
            return expectedTopLevel;
        } catch (IOException exception) {
            throw failure(GitChangeFailureType.INVALID_WORKTREE,
                    "无法解析任务隔离目录的真实路径。", exception.getMessage());
        }
    }

    /** 确认用于比较的基准提交在 Worktree 中仍然可用。 */
    private void validateBaseCommit(Path worktree, String baseCommit) {
        CommandResult result = runGit(worktree,
                List.of("cat-file", "-e", baseCommit + "^{commit}"), METADATA_OUTPUT_LIMIT);
        if (!result.success()) {
            throw failure(GitChangeFailureType.BASE_COMMIT_UNAVAILABLE,
                    "任务基准提交已不可用，无法可靠计算代码变更。", result.outputAsText());
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
    private CommandResult requireSuccess(CommandResult result, String userMessage, GitChangeFailureType failureType) {
        if (!result.success() || result.truncated()) {
            throw failure(failureType, userMessage, result.outputAsText());
        }
        return result;
    }

    /** 创建表示 Diff 超过大小上限的异常。 */
    private GitChangeException diffTooLarge() {
        return failure(GitChangeFailureType.DIFF_TOO_LARGE,
                "本次代码变更超过平台展示上限，请缩小任务范围后重试。",
                "maxDiffBytes=" + maxDiffBytes);
    }

    /** 执行一次带输出上限和超时限制的 Git 命令。 */
    private CommandResult runGit(Path workingDirectory, List<String> arguments, int outputLimit) {
        List<String> command = new ArrayList<>();
        command.add(gitExecutable);
        command.add("-c");
        command.add("core.quotepath=false");
        command.add("-C");
        command.add(workingDirectory.toString());
        command.addAll(arguments);
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
            processBuilder.environment().keySet().removeIf(key -> key.startsWith("GIT_"));
            processBuilder.environment().put("GIT_TERMINAL_PROMPT", "0");
            Process process = processBuilder.start();
            LimitedOutput output;
            try (InputStream input = process.getInputStream()) {
                output = readLimited(input, outputLimit);
            }
            if (!process.waitFor(commandTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw failure(GitChangeFailureType.GIT_COMMAND_FAILED,
                        "Git 变更采集命令执行超时。", String.join(" ", command));
            }
            return new CommandResult(process.exitValue(), output.bytes(), output.truncated());
        } catch (IOException exception) {
            throw failure(GitChangeFailureType.GIT_COMMAND_FAILED,
                    "无法执行 Git，请确认 Git 已安装并加入 PATH。", exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure(GitChangeFailureType.GIT_COMMAND_FAILED,
                    "Git 变更采集被中断。", exception.getMessage());
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
    private GitChangeException failure(GitChangeFailureType type, String userMessage, String diagnostic) {
        return new GitChangeException(type, userMessage, diagnostic);
    }

    private record LimitedOutput(byte[] bytes, boolean truncated) {
    }

    private record CommandResult(int exitCode, byte[] output, boolean truncated) {
        /** 判断 Git 命令是否正常退出。 */
        private boolean success() {
            return exitCode == 0;
        }

        /** 将 Git 输出按 UTF-8 转换为诊断文本。 */
        private String outputAsText() {
            return new String(output, StandardCharsets.UTF_8);
        }
    }
}
