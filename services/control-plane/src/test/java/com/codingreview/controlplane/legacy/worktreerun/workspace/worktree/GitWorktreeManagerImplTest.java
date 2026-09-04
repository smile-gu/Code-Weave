package com.codingreview.controlplane.legacy.worktreerun.workspace.worktree;

import com.codingreview.controlplane.legacy.worktreerun.workspace.worktree.error.WorktreeException;
import com.codingreview.controlplane.legacy.worktreerun.workspace.worktree.error.WorktreeFailureType;
import com.codingreview.controlplane.legacy.worktreerun.workspace.worktree.model.WorktreeCreateRequest;
import com.codingreview.controlplane.legacy.worktreerun.workspace.worktree.model.WorktreeCreateResult;
import com.codingreview.controlplane.legacy.worktreerun.workspace.worktree.model.WorktreeNoticeType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 使用真实 Git 仓库验证 Worktree 隔离、提醒和清理边界。 */
@Tag("legacy-worktree-run")
class GitWorktreeManagerImplTest {

    private static final String GIT = "git";

    @TempDir
    Path tempDir;

    @Test
    void createsDetachedWorktreeAndWarnsWhenSourceRepositoryIsDirty() throws Exception {
        Path repository = createRepository();
        Files.writeString(repository.resolve("local-change.txt"), "only in source", StandardCharsets.UTF_8);
        GitWorktreeManager manager = new GitWorktreeManagerImpl(tempDir.resolve("runtime"));

        WorktreeCreateResult result = manager.create(
                new WorktreeCreateRequest(repository, "run-1", "HEAD"));

        assertThat(result.handle().worktreePath()).exists().isDirectory();
        assertThat(result.handle().sourceRepositoryDirty()).isTrue();
        assertThat(result.notices())
                .extracting(notice -> notice.type())
                .containsExactly(WorktreeNoticeType.SOURCE_REPOSITORY_DIRTY);
        assertThat(result.handle().worktreePath().resolve("local-change.txt")).doesNotExist();
        assertThat(readGit(repository, List.of("worktree", "list")))
                .contains(result.handle().worktreePath().toString().replace('\\', '/'));

        manager.cleanup(result.handle());
        assertThat(result.handle().worktreePath()).doesNotExist();
    }

    @Test
    void refusesNormalCleanupForDirtyWorktreeButAllowsExplicitForceCleanup() throws Exception {
        Path repository = createRepository();
        GitWorktreeManager manager = new GitWorktreeManagerImpl(tempDir.resolve("runtime"));
        WorktreeCreateResult result = manager.create(
                new WorktreeCreateRequest(repository, "run-2", "HEAD"));
        Files.writeString(result.handle().worktreePath().resolve("agent-change.txt"), "pending", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> manager.cleanup(result.handle()))
                .isInstanceOf(WorktreeException.class)
                .satisfies(error -> {
                    WorktreeException exception = (WorktreeException) error;
                    assertThat(exception.failureType()).isEqualTo(WorktreeFailureType.CLEANUP_REJECTED_DIRTY);
                    assertThat(exception.userMessage()).contains("还有未提交修改");
                    assertThat(exception.diagnostic()).contains("agent-change.txt");
                });

        manager.forceCleanup(result.handle());
        assertThat(result.handle().worktreePath()).doesNotExist();
    }

    @Test
    void rejectsInvalidBaseRefBeforeCreatingWorktree() throws Exception {
        Path repository = createRepository();
        GitWorktreeManager manager = new GitWorktreeManagerImpl(tempDir.resolve("runtime"));

        assertThatThrownBy(() -> manager.create(
                new WorktreeCreateRequest(repository, "run-3", "refs/heads/does-not-exist")))
                .isInstanceOf(WorktreeException.class)
                .satisfies(error -> {
                    WorktreeException exception = (WorktreeException) error;
                    assertThat(exception.failureType()).isEqualTo(WorktreeFailureType.INVALID_BASE_REF);
                    assertThat(exception.userMessage()).contains("基准版本");
                });
        assertThat(tempDir.resolve("runtime/worktrees/run-3")).doesNotExist();
    }

    @Test
    void reportsRepositoryStatusWithoutChangingIt() throws Exception {
        Path repository = createRepository();

        GitWorktreeManager manager = new GitWorktreeManagerImpl(tempDir.resolve("runtime"));
        assertThat(manager.inspect(repository).dirty()).isFalse();
        assertThat(manager.inspect(repository).headCommit()).isNotBlank();

        Files.writeString(repository.resolve("untracked.txt"), "pending", StandardCharsets.UTF_8);
        assertThat(manager.inspect(repository).dirty()).isTrue();
    }

    @Test
    void rejectsNonGitDirectoryWithUserFriendlyFailure() throws Exception {
        // 当前用户临时目录位于一个更大的 Git 仓库内，使用 D: 根目录验证真正的非仓库边界。
        Path nonGitDirectory = Path.of("D:/");
        GitWorktreeManager manager = new GitWorktreeManagerImpl(tempDir.resolve("runtime"));

        assertThatThrownBy(() -> manager.inspect(nonGitDirectory))
                .isInstanceOf(WorktreeException.class)
                .satisfies(error -> {
                    WorktreeException exception = (WorktreeException) error;
                    assertThat(exception.failureType()).isEqualTo(WorktreeFailureType.NOT_GIT_REPOSITORY);
                    assertThat(exception.userMessage()).contains("不是有效的 Git 仓库");
                });
    }

    private Path createRepository() throws Exception {
        Path repository = tempDir.resolve("source-repository");
        Files.createDirectories(repository);
        runGit(repository, List.of("init", "-b", "main"));
        Files.writeString(repository.resolve("README.md"), "fixture", StandardCharsets.UTF_8);
        runGit(repository, List.of("add", "README.md"));
        runGit(repository, List.of("-c", "user.name=Code Weave Test", "-c",
                "user.email=test@code-weave.local", "commit", "-m", "initial commit"));
        return repository;
    }

    private String readGit(Path repository, List<String> arguments) throws Exception {
        return runGit(repository, arguments);
    }

    private String runGit(Path repository, List<String> arguments) throws Exception {
        ProcessBuilder builder = new ProcessBuilder();
        builder.command(buildCommand(repository, arguments));
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output;
        try (var input = process.getInputStream()) {
            output = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue())
                .withFailMessage("Git command failed: %s", output)
                .isZero();
        return output;
    }

    private List<String> buildCommand(Path repository, List<String> arguments) {
        var command = new java.util.ArrayList<String>();
        command.add(GIT);
        command.add("-C");
        command.add(repository.toString());
        command.addAll(arguments);
        return command;
    }
}
