package com.codingreview.controlplane.workspace.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证真实 Git 仓库根目录、分支、HEAD 和脏状态检查。 */
class GitRepositoryInspectorImplTest {
    @TempDir Path tempDir;

    @Test
    void readsCleanAndDirtyRepositorySnapshot() throws Exception {
        Path repository = createRepository();
        GitRepositoryInspector inspector = new GitRepositoryInspectorImpl();

        var clean = inspector.inspect(repository);
        assertThat(clean.repositoryRoot()).isEqualTo(repository.toRealPath());
        assertThat(clean.branch()).isEqualTo("main");
        assertThat(clean.headCommit()).hasSize(40);
        assertThat(clean.dirty()).isFalse();

        Files.writeString(repository.resolve("local.txt"), "pending", StandardCharsets.UTF_8);
        assertThat(inspector.inspect(repository).dirty()).isTrue();
    }

    @Test
    void rejectsRepositorySubdirectory() throws Exception {
        Path repository = createRepository();
        Path child = Files.createDirectories(repository.resolve("src"));

        assertThatThrownBy(() -> new GitRepositoryInspectorImpl().inspect(child))
                .isInstanceOf(GitRepositoryException.class)
                .satisfies(error -> assertThat(((GitRepositoryException) error).code())
                        .isEqualTo("REPOSITORY_NOT_ROOT"));
    }

    private Path createRepository() throws Exception {
        Path repository = tempDir.resolve("repository").toAbsolutePath();
        Files.createDirectories(repository);
        git(repository, "init", "-b", "main");
        git(repository, "config", "user.name", "Code Weave Test");
        git(repository, "config", "user.email", "code-weave@example.invalid");
        Files.writeString(repository.resolve("README.md"), "fixture", StandardCharsets.UTF_8);
        git(repository, "add", "README.md");
        git(repository, "commit", "-m", "fixture");
        return repository;
    }

    private void git(Path repository, String... arguments) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add("git"); command.add("-C"); command.add(repository.toString());
        command.addAll(java.util.List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
    }
}
