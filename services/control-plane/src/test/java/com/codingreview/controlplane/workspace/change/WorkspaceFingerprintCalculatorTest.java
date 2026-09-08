package com.codingreview.controlplane.workspace.change;

import com.codingreview.controlplane.workspace.change.model.GitChangeSet;
import com.codingreview.controlplane.workspace.change.model.GitChangeType;
import com.codingreview.controlplane.workspace.change.model.GitChangedFile;
import com.codingreview.controlplane.workspace.model.WorkspaceHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证同一份最终代码在不同 Git 暂存状态下具有相同指纹。 */
class WorkspaceFingerprintCalculatorTest {

    @TempDir
    Path tempDir;

    @Test
    void keepsFingerprintStableAcrossUnstagedStagedAndCommittedStates() throws Exception {
        Path repository = tempDir.resolve("fingerprint-repository");
        Files.createDirectories(repository);
        runGit(repository, List.of("init", "-b", "main"));
        Files.writeString(repository.resolve("README.md"), "before\n", StandardCharsets.UTF_8);
        runGit(repository, List.of("add", "."));
        runGit(repository, List.of("-c", "user.name=Code Weave Test", "-c",
                "user.email=test@code-weave.local", "commit", "-m", "initial"));
        String baseline = runGit(repository, List.of("rev-parse", "HEAD")).trim();
        Files.writeString(repository.resolve("README.md"), "after\n", StandardCharsets.UTF_8);

        GitChangeCollector collector = new GitChangeCollectorImpl();
        WorkspaceFingerprintCalculator calculator = new WorkspaceFingerprintCalculator();
        String unstaged = fingerprint(collector, calculator, repository, baseline);

        runGit(repository, List.of("add", "README.md"));
        String staged = fingerprint(collector, calculator, repository, baseline);

        runGit(repository, List.of("-c", "user.name=Code Weave Test", "-c",
                "user.email=test@code-weave.local", "commit", "-m", "change"));
        String committed = fingerprint(collector, calculator, repository, baseline);

        assertThat(staged).isEqualTo(unstaged);
        assertThat(committed).isEqualTo(unstaged);
    }

    @Test
    void changesFingerprintWhenFinalFileContentChanges() throws Exception {
        Path repository = tempDir.resolve("content-repository");
        Files.createDirectories(repository);
        runGit(repository, List.of("init", "-b", "main"));
        Files.writeString(repository.resolve("README.md"), "before\n", StandardCharsets.UTF_8);
        runGit(repository, List.of("add", "."));
        runGit(repository, List.of("-c", "user.name=Code Weave Test", "-c",
                "user.email=test@code-weave.local", "commit", "-m", "initial"));
        String baseline = runGit(repository, List.of("rev-parse", "HEAD")).trim();

        Files.writeString(repository.resolve("README.md"), "after-one\n", StandardCharsets.UTF_8);
        GitChangeCollector collector = new GitChangeCollectorImpl();
        WorkspaceFingerprintCalculator calculator = new WorkspaceFingerprintCalculator();
        String first = fingerprint(collector, calculator, repository, baseline);
        Files.writeString(repository.resolve("README.md"), "after-two\n", StandardCharsets.UTF_8);
        String second = fingerprint(collector, calculator, repository, baseline);

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void changesFingerprintWhenBinaryContentChanges() throws Exception {
        Path repository = tempDir.resolve("binary-repository");
        Files.createDirectories(repository);
        runGit(repository, List.of("init", "-b", "main"));
        Files.write(repository.resolve("asset.bin"), new byte[]{1, 2, 3});
        runGit(repository, List.of("add", "."));
        runGit(repository, List.of("-c", "user.name=Code Weave Test", "-c",
                "user.email=test@code-weave.local", "commit", "-m", "initial"));
        String baseline = runGit(repository, List.of("rev-parse", "HEAD")).trim();

        GitChangeCollector collector = new GitChangeCollectorImpl();
        WorkspaceFingerprintCalculator calculator = new WorkspaceFingerprintCalculator();
        Files.write(repository.resolve("asset.bin"), new byte[]{1, 2, 4});
        String first = fingerprint(collector, calculator, repository, baseline);
        Files.write(repository.resolve("asset.bin"), new byte[]{1, 2, 5});
        String second = fingerprint(collector, calculator, repository, baseline);

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void keepsRenameFingerprintStableAcrossUnstagedStagedAndCommittedStates() throws Exception {
        Path repository = initializeRepository("rename-repository", "old-name.txt", "same content\n");
        String baseline = runGit(repository, List.of("rev-parse", "HEAD")).trim();
        Files.move(repository.resolve("old-name.txt"), repository.resolve("new-name.txt"));
        GitChangeCollector collector = new GitChangeCollectorImpl();
        WorkspaceFingerprintCalculator calculator = new WorkspaceFingerprintCalculator();

        String unstaged = fingerprint(collector, calculator, repository, baseline);
        runGit(repository, List.of("add", "-A"));
        String staged = fingerprint(collector, calculator, repository, baseline);
        runGit(repository, List.of("-c", "user.name=Code Weave Test", "-c",
                "user.email=test@code-weave.local", "commit", "-m", "rename"));
        String committed = fingerprint(collector, calculator, repository, baseline);

        assertThat(staged).isEqualTo(unstaged);
        assertThat(committed).isEqualTo(unstaged);
    }

    @Test
    void changesFingerprintWhenGitExecutableModeChangesOnWindows() throws Exception {
        Path repository = initializeRepository("mode-repository", "script.sh", "echo before\n");
        String baseline = runGit(repository, List.of("rev-parse", "HEAD")).trim();
        Files.writeString(repository.resolve("script.sh"), "echo after\n", StandardCharsets.UTF_8);
        GitChangeCollector collector = new GitChangeCollectorImpl();
        WorkspaceFingerprintCalculator calculator = new WorkspaceFingerprintCalculator();

        String regular = fingerprint(collector, calculator, repository, baseline);
        runGit(repository, List.of("update-index", "--chmod=+x", "script.sh"));
        String executable = fingerprint(collector, calculator, repository, baseline);

        assertThat(executable).isNotEqualTo(regular);
    }

    @Test
    void rejectsMissingWorkspaceInsteadOfCreatingFallbackFingerprint() {
        Path missingWorkspace = tempDir.resolve("missing-workspace");
        GitChangeSet changes = new GitChangeSet(
                "base", missingWorkspace,
                List.of(new GitChangedFile("file.txt", null, GitChangeType.ADDED, false)),
                "diff", 4
        );

        assertThatThrownBy(() -> new WorkspaceFingerprintCalculator().calculate(changes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("工作区");
    }

    @Test
    void sortsFilesAndDoesNotDependOnCombinedPatchSeparator() throws Exception {
        Path workspace = tempDir.resolve("synthetic");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("z.txt"), "z\n", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("a.txt"), "a\n", StandardCharsets.UTF_8);
        GitChangedFile first = new GitChangedFile("z.txt", null, GitChangeType.ADDED, false);
        GitChangedFile second = new GitChangedFile("a.txt", null, GitChangeType.ADDED, false);
        WorkspaceFingerprintCalculator calculator = new WorkspaceFingerprintCalculator();
        GitChangeSet ordered = new GitChangeSet("base", workspace,
                List.of(first, second), "diff-z\ndiff-a", 15);
        GitChangeSet reversed = new GitChangeSet("base", workspace,
                List.of(second, first), "diff-z\n\ndiff-a", 16);

        assertThat(calculator.calculate(ordered)).isEqualTo(calculator.calculate(reversed));
    }

    /** 采集当前工作区并计算规范化指纹。 */
    private String fingerprint(
            GitChangeCollector collector,
            WorkspaceFingerprintCalculator calculator,
            Path repository,
            String baseline
    ) {
        GitChangeSet changes = collector.collect(new WorkspaceHandle(repository, baseline));
        return calculator.calculate(changes);
    }

    /** 创建包含一个初始提交的真实 Git 仓库。 */
    private Path initializeRepository(String name, String file, String content) throws Exception {
        Path repository = tempDir.resolve(name);
        Files.createDirectories(repository);
        runGit(repository, List.of("init", "-b", "main"));
        Files.writeString(repository.resolve(file), content, StandardCharsets.UTF_8);
        runGit(repository, List.of("add", "."));
        runGit(repository, List.of("-c", "user.name=Code Weave Test", "-c",
                "user.email=test@code-weave.local", "commit", "-m", "initial"));
        return repository;
    }

    /** 执行测试所需的 Git 命令。 */
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
}
