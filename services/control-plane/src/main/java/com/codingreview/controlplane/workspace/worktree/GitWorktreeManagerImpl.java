package com.codingreview.controlplane.workspace.worktree;

import com.codingreview.controlplane.workspace.worktree.error.WorktreeException;
import com.codingreview.controlplane.workspace.worktree.error.WorktreeFailureType;
import com.codingreview.controlplane.workspace.worktree.model.GitRepositoryStatus;
import com.codingreview.controlplane.workspace.worktree.model.WorktreeCreateRequest;
import com.codingreview.controlplane.workspace.worktree.model.WorktreeCreateResult;
import com.codingreview.controlplane.workspace.worktree.model.WorktreeHandle;
import com.codingreview.controlplane.workspace.worktree.model.WorktreeNotice;
import com.codingreview.controlplane.workspace.worktree.model.WorktreeNoticeType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** 使用系统 Git CLI 管理隔离 Worktree。 */
public final class GitWorktreeManagerImpl implements GitWorktreeManager {

    private static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofSeconds(30);

    private final Path runtimeRoot;
    private final String gitExecutable;
    private final Duration commandTimeout;

    public GitWorktreeManagerImpl(Path runtimeRoot) {
        this(runtimeRoot, "git", DEFAULT_COMMAND_TIMEOUT);
    }

    public GitWorktreeManagerImpl(Path runtimeRoot, String gitExecutable, Duration commandTimeout) {
        Objects.requireNonNull(runtimeRoot, "runtimeRoot");
        if (!runtimeRoot.isAbsolute()) {
            throw new IllegalArgumentException("runtimeRoot must be absolute");
        }
        if (gitExecutable == null || gitExecutable.isBlank()) {
            throw new IllegalArgumentException("gitExecutable must not be blank");
        }
        Objects.requireNonNull(commandTimeout, "commandTimeout");
        if (commandTimeout.isZero() || commandTimeout.isNegative()) {
            throw new IllegalArgumentException("commandTimeout must be positive");
        }
        this.runtimeRoot = runtimeRoot.normalize();
        this.gitExecutable = gitExecutable;
        this.commandTimeout = commandTimeout;
    }

    @Override
    public GitRepositoryStatus inspect(Path repository) {
        Path normalizedRepository = resolveRepository(repository);
        CommandResult status = runGit(normalizedRepository,
                List.of("status", "--porcelain", "--untracked-files=all"));
        if (!status.success()) {
            throw failure(WorktreeFailureType.GIT_COMMAND_FAILED,
                    "无法读取 Git 仓库状态，请稍后重试。", status.output());
        }
        CommandResult head = runGit(normalizedRepository, List.of("rev-parse", "HEAD"));
        return new GitRepositoryStatus(normalizedRepository, !status.output().isBlank(),
                head.success() ? head.output().trim() : null);
    }

    @Override
    public WorktreeCreateResult create(WorktreeCreateRequest request) {
        Objects.requireNonNull(request, "request");
        Path repository = resolveRepository(request.repository());
        CommandResult resolvedRef = runGit(repository,
                List.of("rev-parse", "--verify", request.baseRef() + "^{commit}"));
        if (!resolvedRef.success() || resolvedRef.output().isBlank()) {
            throw failure(WorktreeFailureType.INVALID_BASE_REF,
                    "找不到指定的基准版本，无法创建本次任务环境。",
                    resolvedRef.output());
        }

        GitRepositoryStatus repositoryStatus = inspect(repository);
        Path worktreePath = runtimeRoot.resolve("worktrees").resolve(request.runId()).normalize();
        Path worktreeRoot = runtimeRoot.resolve("worktrees").normalize();
        if (!worktreePath.startsWith(worktreeRoot)) {
            throw failure(WorktreeFailureType.INVALID_WORKTREE_PATH,
                    "任务标识生成的 Worktree 路径不安全，无法继续。", worktreePath.toString());
        }
        if (Files.exists(worktreePath)) {
            throw failure(WorktreeFailureType.WORKTREE_ALREADY_EXISTS,
                    "本次任务的隔离目录已经存在，请更换任务标识后重试。", worktreePath.toString());
        }

        try {
            Files.createDirectories(worktreeRoot);
        } catch (IOException exception) {
            throw failure(WorktreeFailureType.INVALID_WORKTREE_PATH,
                    "无法准备任务隔离目录，请检查运行目录权限。", exception.getMessage());
        }

        CommandResult created = runGit(repository, List.of(
                "worktree", "add", "--detach", worktreePath.toString(), resolvedRef.output().trim()));
        if (!created.success()) {
            throw failure(WorktreeFailureType.CREATE_FAILED,
                    "隔离 Worktree 创建失败，原始仓库未被修改。", created.output());
        }

        WorktreeHandle handle = new WorktreeHandle(repository, worktreePath, request.runId(),
                request.baseRef(), resolvedRef.output().trim(), repositoryStatus.dirty());
        List<WorktreeNotice> notices = new ArrayList<>();
        if (repositoryStatus.dirty()) {
            notices.add(new WorktreeNotice(WorktreeNoticeType.SOURCE_REPOSITORY_DIRTY,
                    "检测到原始仓库存在未提交修改。本次任务基于已提交的基准版本创建，" +
                            "这些未提交修改不会进入隔离 Worktree。"));
        }
        return new WorktreeCreateResult(handle, notices);
    }

    @Override
    public void cleanup(WorktreeHandle handle) {
        validateHandle(handle);
        if (!Files.exists(handle.worktreePath())) {
            return;
        }
        CommandResult status = runGit(handle.worktreePath(),
                List.of("status", "--porcelain", "--untracked-files=all"));
        if (!status.success()) {
            throw failure(WorktreeFailureType.CLEANUP_FAILED,
                    "无法确认隔离目录是否还有修改，已停止清理以保护你的代码。", status.output());
        }
        if (!status.output().isBlank()) {
            throw failure(WorktreeFailureType.CLEANUP_REJECTED_DIRTY,
                    "隔离 Worktree 中还有未提交修改，普通清理已停止。请先确认修改已保存或明确丢弃。",
                    status.output());
        }
        removeWorktree(handle, false);
    }

    @Override
    public void forceCleanup(WorktreeHandle handle) {
        validateHandle(handle);
        if (Files.exists(handle.worktreePath())) {
            removeWorktree(handle, true);
        }
    }

    private void removeWorktree(WorktreeHandle handle, boolean force) {
        List<String> command = new ArrayList<>(List.of("worktree", "remove"));
        if (force) {
            command.add("--force");
        }
        command.add(handle.worktreePath().toString());
        CommandResult removed = runGit(handle.sourceRepository(), command);
        if (!removed.success() || Files.exists(handle.worktreePath())) {
            throw failure(WorktreeFailureType.CLEANUP_FAILED,
                    force ? "隔离 Worktree 强制清理失败，请保留现场并检查技术诊断。"
                            : "隔离 Worktree 清理失败，原始仓库未被修改。",
                    removed.output());
        }
    }

    private Path resolveRepository(Path repository) {
        if (repository == null || !repository.isAbsolute() || !Files.isDirectory(repository)) {
            throw failure(WorktreeFailureType.INVALID_REPOSITORY,
                    "指定的仓库目录不存在或不可访问。", String.valueOf(repository));
        }
        CommandResult topLevel = runGit(repository, List.of("rev-parse", "--show-toplevel"));
        if (!topLevel.success() || topLevel.output().isBlank()) {
            throw failure(WorktreeFailureType.NOT_GIT_REPOSITORY,
                    "指定目录不是有效的 Git 仓库，请选择包含 .git 的项目目录。", topLevel.output());
        }
        try {
            return Path.of(topLevel.output().trim()).toRealPath();
        } catch (IOException exception) {
            throw failure(WorktreeFailureType.INVALID_REPOSITORY,
                    "无法解析 Git 仓库的真实路径。", exception.getMessage());
        }
    }

    private void validateHandle(WorktreeHandle handle) {
        Objects.requireNonNull(handle, "handle");
        Path expectedRoot = runtimeRoot.resolve("worktrees").normalize();
        Path sourceRepository = resolveRepository(handle.sourceRepository());
        if (!handle.sourceRepository().isAbsolute()
                || !handle.worktreePath().isAbsolute()
                || !sourceRepository.equals(handle.sourceRepository().normalize())
                || !handle.worktreePath().normalize().startsWith(expectedRoot)
                || !handle.worktreePath().getFileName().toString().equals(handle.runId())) {
            throw failure(WorktreeFailureType.INVALID_WORKTREE_PATH,
                    "隔离目录句柄不属于当前运行环境，拒绝执行清理。", handle.worktreePath().toString());
        }
    }

    private CommandResult runGit(Path repository, List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add(gitExecutable);
        command.add("-C");
        command.add(repository.toString());
        command.addAll(arguments);
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
            // 清除外部 Git 上下文，避免 GIT_DIR 等变量让命令误操作其他仓库。
            processBuilder.environment().keySet().removeIf(key -> key.startsWith("GIT_"));
            Process process = processBuilder.start();
            String output;
            try (var input = process.getInputStream()) {
                output = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            if (!process.waitFor(commandTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw failure(WorktreeFailureType.GIT_COMMAND_FAILED,
                        "Git 命令执行超时，操作已停止。", String.join(" ", command));
            }
            return new CommandResult(process.exitValue(), output);
        } catch (IOException exception) {
            throw failure(WorktreeFailureType.GIT_COMMAND_FAILED,
                    "无法执行 Git，请确认 Git 已安装并加入 PATH。", exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure(WorktreeFailureType.GIT_COMMAND_FAILED,
                    "Git 操作被中断，任务未继续执行。", exception.getMessage());
        }
    }

    private WorktreeException failure(WorktreeFailureType type, String userMessage, String diagnostic) {
        return new WorktreeException(type, userMessage, diagnostic);
    }

    private record CommandResult(int exitCode, String output) {
        private boolean success() {
            return exitCode == 0;
        }
    }
}
