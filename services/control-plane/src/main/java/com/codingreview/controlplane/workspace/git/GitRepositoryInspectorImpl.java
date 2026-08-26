package com.codingreview.controlplane.workspace.git;

import com.codingreview.controlplane.workspace.git.model.GitRepositorySnapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** 通过本机 Git CLI 读取真实仓库状态，不创建或切换 Worktree。 */
public final class GitRepositoryInspectorImpl implements GitRepositoryInspector {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(15);
    private static final int OUTPUT_LIMIT = 4 * 1024 * 1024;

    /** 校验仓库并读取当前分支、HEAD 和工作区脏状态。 */
    @Override
    public GitRepositorySnapshot inspect(Path repository) {
        Path requested = normalize(repository);
        String rootText = git(requested, "rev-parse", "--show-toplevel");
        Path root = Path.of(rootText).toAbsolutePath().normalize();
        if (!root.equals(requested)) {
            throw new GitRepositoryException("REPOSITORY_NOT_ROOT", "请选择 Git 仓库根目录，而不是其子目录。", root.toString());
        }
        String bare = git(root, "rev-parse", "--is-bare-repository");
        if ("true".equalsIgnoreCase(bare)) {
            throw new GitRepositoryException("BARE_REPOSITORY_UNSUPPORTED", "暂不支持 bare Git 仓库。", root.toString());
        }
        String branch = git(root, "branch", "--show-current");
        if (branch.isBlank()) {
            throw new GitRepositoryException("DETACHED_HEAD", "当前仓库处于 detached HEAD，无法创建 Coding Session。", root.toString());
        }
        String head = git(root, "rev-parse", "HEAD");
        boolean dirty = !git(root, "status", "--porcelain", "--untracked-files=all").isBlank();
        return new GitRepositorySnapshot(root, branch, head, dirty);
    }

    /** 规范化用户传入的目录并校验其存在性。 */
    private Path normalize(Path repository) {
        Objects.requireNonNull(repository, "repository");
        if (!repository.isAbsolute() || !Files.isDirectory(repository)) {
            throw new GitRepositoryException("REPOSITORY_NOT_FOUND", "指定的路径不是可用目录。", String.valueOf(repository));
        }
        try {
            return repository.toRealPath();
        } catch (IOException exception) {
            throw new GitRepositoryException("REPOSITORY_NOT_FOUND", "无法解析仓库真实路径。", exception.getMessage(), exception);
        }
    }

    /** 执行只读 Git 命令并统一转换失败信息。 */
    private String git(Path directory, String... arguments) {
        List<String> command = new ArrayList<>(List.of("git", "-C", directory.toString()));
        command.addAll(List.of(arguments));
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            builder.environment().keySet().removeIf(key -> key.startsWith("GIT_"));
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            Process process = builder.start();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<byte[]> outputFuture = executor.submit(
                        () -> readLimited(process.getInputStream()));
                if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    throw new GitRepositoryException("GIT_COMMAND_TIMEOUT", "Git 检查超时，已停止本次操作。", String.join(" ", command));
                }
                String output = new String(outputFuture.get(), StandardCharsets.UTF_8).trim();
                if (process.exitValue() != 0) {
                    throw new GitRepositoryException("INVALID_GIT_REPOSITORY", "指定目录不是可用的 Git 仓库。", output);
                }
                return output;
            }
        } catch (IOException exception) {
            throw new GitRepositoryException("GIT_UNAVAILABLE", "无法执行 Git，请确认 Git 已安装并加入 PATH。", exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GitRepositoryException("GIT_INTERRUPTED", "Git 检查被中断。", exception.getMessage(), exception);
        } catch (java.util.concurrent.ExecutionException exception) {
            throw new GitRepositoryException("GIT_OUTPUT_FAILED", "无法读取 Git 检查结果。", exception.getMessage(), exception);
        }
    }

    /** 读取并排空 Git 输出，同时限制保留在内存中的诊断大小。 */
    private byte[] readLimited(java.io.InputStream input) throws IOException {
        try (input; var output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                int remaining = OUTPUT_LIMIT - output.size();
                if (remaining > 0) output.write(buffer, 0, Math.min(read, remaining));
            }
            return output.toByteArray();
        }
    }

}
