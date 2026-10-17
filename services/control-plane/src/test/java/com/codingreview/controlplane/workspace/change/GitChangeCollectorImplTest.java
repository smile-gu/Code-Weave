package com.codingreview.controlplane.workspace.change;

import com.codingreview.controlplane.workspace.change.error.GitChangeException;
import com.codingreview.controlplane.workspace.change.error.GitChangeFailureType;
import com.codingreview.controlplane.workspace.change.model.GitChangeSet;
import com.codingreview.controlplane.workspace.change.model.GitChangeType;
import com.codingreview.controlplane.workspace.change.model.GitChangedFile;
import com.codingreview.controlplane.workspace.model.WorkspaceHandle;
import com.codingreview.controlplane.legacy.worktreerun.workspace.worktree.GitWorktreeManager;
import com.codingreview.controlplane.legacy.worktreerun.workspace.worktree.GitWorktreeManagerImpl;
import com.codingreview.controlplane.legacy.worktreerun.workspace.worktree.model.WorktreeCreateRequest;
import com.codingreview.controlplane.legacy.worktreerun.workspace.worktree.model.WorktreeHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 使用真实 Git Worktree 验证最终代码变更采集。 */
class GitChangeCollectorImplTest {

    @TempDir
    Path tempDir;

    @Test
    void collectsChangesDirectlyFromRepositoryRoot() throws Exception {
        Path repository = tempDir.resolve("direct-repository");
        Files.createDirectories(repository);
        runGit(repository, List.of("init", "-b", "main"));
        Files.writeString(repository.resolve("README.md"), "before\n", StandardCharsets.UTF_8);
        runGit(repository, List.of("add", "."));
        runGit(repository, List.of("-c", "user.name=Code Weave Test", "-c",
                "user.email=test@code-weave.local", "commit", "-m", "initial commit"));
        String baselineCommit = runGit(repository, List.of("rev-parse", "HEAD")).trim();
        Files.writeString(repository.resolve("README.md"), "after\n", StandardCharsets.UTF_8);

        GitChangeSet changeSet = new GitChangeCollectorImpl().collect(
                new WorkspaceHandle(repository.toAbsolutePath(), baselineCommit)
        );

        assertThat(changeSet.workspaceRoot()).isEqualTo(repository.toRealPath());
        assertThat(changeSet.files()).singleElement().satisfies(file ->
                assertThat(file.path()).isEqualTo("README.md")
        );
    }

    @Test
    void collectsTrackedUntrackedAndGitDetectedRenameChanges() throws Exception {
        TestWorkspace workspace = createWorkspace("run-changes");
        Path worktree = workspace.handle().worktreePath();
        Files.writeString(worktree.resolve("modify.txt"), "after\n", StandardCharsets.UTF_8);
        Files.delete(worktree.resolve("delete.txt"));
        Files.move(worktree.resolve("rename-old.txt"), worktree.resolve("rename-new.txt"));
        // 暂存仅用于验证 Git 原生重命名信息；Collector 本身不会修改真实索引。
        runGit(worktree, List.of("add", "-A"));
        runGit(worktree, List.of("config", "core.autocrlf", "true"));
        Files.createDirectories(worktree.resolve("src"));
        Files.writeString(worktree.resolve("src/NewFile.java"), "class NewFile {}\n", StandardCharsets.UTF_8);
        String statusBeforeCollection = runGit(worktree,
                List.of("status", "--porcelain", "--untracked-files=all"));

        GitChangeSet changeSet = new GitChangeCollectorImpl().collect(handle(workspace));

        assertThat(changeSet.baseCommit()).isEqualTo(workspace.handle().resolvedCommit());
        assertThat(changeSet.files()).extracting(GitChangedFile::path)
                .containsExactly("delete.txt", "modify.txt", "rename-new.txt", "src/NewFile.java");
        assertThat(find(changeSet, "modify.txt").changeType()).isEqualTo(GitChangeType.MODIFIED);
        assertThat(find(changeSet, "delete.txt").changeType()).isEqualTo(GitChangeType.DELETED);
        assertThat(find(changeSet, "rename-new.txt").changeType()).isEqualTo(GitChangeType.RENAMED);
        assertThat(find(changeSet, "rename-new.txt").oldPath()).isEqualTo("rename-old.txt");
        assertThat(find(changeSet, "src/NewFile.java").changeType()).isEqualTo(GitChangeType.ADDED);
        assertThat(changeSet.unifiedDiff())
                .startsWith("diff --git")
                .doesNotContain("warning:")
                .contains("modify.txt", "rename-new.txt", "src/NewFile.java", "class NewFile");
        assertThat(changeSet.unifiedDiffBytes()).isPositive();
        assertThat(runGit(worktree, List.of("status", "--porcelain", "--untracked-files=all")))
                .isEqualTo(statusBeforeCollection);
    }

    @Test
    void returnsEmptyChangeSetAndExcludesIgnoredFiles() throws Exception {
        TestWorkspace workspace = createWorkspace("run-empty");
        Path ignored = workspace.handle().worktreePath().resolve("target/generated.txt");
        Files.createDirectories(ignored.getParent());
        Files.writeString(ignored, "ignored", StandardCharsets.UTF_8);

        GitChangeSet changeSet = new GitChangeCollectorImpl().collect(handle(workspace));

        assertThat(changeSet.isEmpty()).isTrue();
        assertThat(changeSet.files()).isEmpty();
        assertThat(changeSet.unifiedDiff()).isEmpty();
        assertThat(changeSet.unifiedDiffBytes()).isZero();
    }

    @Test
    void marksUntrackedBinaryFileWithoutEmbeddingRawBytesAsText() throws Exception {
        TestWorkspace workspace = createWorkspace("run-binary");
        Path binaryFile = workspace.handle().worktreePath().resolve("asset.bin");
        Files.write(binaryFile, new byte[]{0, 1, 2, 3, 0, 5, 6});

        GitChangeSet changeSet = new GitChangeCollectorImpl().collect(handle(workspace));

        assertThat(changeSet.files()).singleElement().satisfies(file -> {
            assertThat(file.path()).isEqualTo("asset.bin");
            assertThat(file.changeType()).isEqualTo(GitChangeType.ADDED);
            assertThat(file.binary()).isTrue();
        });
        assertThat(changeSet.unifiedDiff())
                .contains("Binary files")
                .doesNotContain("GIT binary patch");
    }

    @Test
    void rejectsDiffThatExceedsConfiguredLimit() throws Exception {
        TestWorkspace workspace = createWorkspace("run-large");
        Files.writeString(workspace.handle().worktreePath().resolve("large.txt"),
                "large-change-line\n".repeat(100), StandardCharsets.UTF_8);
        GitChangeCollector collector = new GitChangeCollectorImpl("git", Duration.ofSeconds(30), 128);

        assertThatThrownBy(() -> collector.collect(handle(workspace)))
                .isInstanceOf(GitChangeException.class)
                .satisfies(error -> {
                    GitChangeException exception = (GitChangeException) error;
                    assertThat(exception.failureType()).isEqualTo(GitChangeFailureType.DIFF_TOO_LARGE);
                    assertThat(exception.userMessage()).contains("展示上限");
                });
    }

    @Test
    void rejectsMissingBaseCommit() throws Exception {
        TestWorkspace workspace = createWorkspace("run-invalid-base");
        WorkspaceHandle invalidHandle = new WorkspaceHandle(
                workspace.handle().worktreePath(),
                "0000000000000000000000000000000000000000"
        );

        assertThatThrownBy(() -> new GitChangeCollectorImpl().collect(invalidHandle))
                .isInstanceOf(GitChangeException.class)
                .satisfies(error -> assertThat(((GitChangeException) error).failureType())
                        .isEqualTo(GitChangeFailureType.BASE_COMMIT_UNAVAILABLE));
    }

    private GitChangedFile find(GitChangeSet changeSet, String path) {
        return changeSet.files().stream()
                .filter(file -> file.path().equals(path))
                .findFirst()
                .orElseThrow();
    }

    /** 将测试创建的 Worktree 转换为通用工作区句柄。 */
    private WorkspaceHandle handle(TestWorkspace workspace) {
        return new WorkspaceHandle(
                workspace.handle().worktreePath(), workspace.handle().resolvedCommit()
        );
    }

    private TestWorkspace createWorkspace(String runId) throws Exception {
        Path repository = tempDir.resolve("repository-" + runId);
        Files.createDirectories(repository);
        runGit(repository, List.of("init", "-b", "main"));
        Files.writeString(repository.resolve("modify.txt"), "before\n", StandardCharsets.UTF_8);
        Files.writeString(repository.resolve("delete.txt"), "delete me\n", StandardCharsets.UTF_8);
        Files.writeString(repository.resolve("rename-old.txt"), "rename content\n", StandardCharsets.UTF_8);
        Files.writeString(repository.resolve(".gitignore"), "target/\n", StandardCharsets.UTF_8);
        runGit(repository, List.of("add", "."));
        runGit(repository, List.of("-c", "user.name=Code Weave Test", "-c",
                "user.email=test@code-weave.local", "commit", "-m", "initial commit"));

        GitWorktreeManager manager = new GitWorktreeManagerImpl(tempDir.resolve("runtime-" + runId));
        WorktreeHandle handle = manager.create(new WorktreeCreateRequest(repository, runId, "HEAD")).handle();
        return new TestWorkspace(manager, handle);
    }

    private String runGit(Path repository, List<String> arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repository.toString());
        command.addAll(arguments);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
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

    private record TestWorkspace(GitWorktreeManager manager, WorktreeHandle handle) {
    }
}
