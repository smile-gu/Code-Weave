package com.codingreview.controlplane.legacy.worktreerun.workflow;

import com.codingreview.controlplane.adapter.claude.ClaudeCodeAdapter;
import com.codingreview.controlplane.adapter.claude.runtime.ClaudeRunResult;
import com.codingreview.controlplane.adapter.claude.runtime.ClaudeRunStatus;
import com.codingreview.controlplane.event.AgentEvent;
import com.codingreview.controlplane.event.AgentEventType;
import com.codingreview.controlplane.observability.run.FileRunEventJournal;
import com.codingreview.controlplane.observability.run.RunEvent;
import com.codingreview.controlplane.observability.run.RunEventType;
import com.codingreview.controlplane.verification.maven.MavenVerifierImpl;
import com.codingreview.controlplane.legacy.worktreerun.workflow.model.WorktreeRunRequest;
import com.codingreview.controlplane.legacy.worktreerun.workflow.model.WorktreeRunResult;
import com.codingreview.controlplane.legacy.worktreerun.workflow.model.WorktreeRunStatus;
import com.codingreview.controlplane.workspace.change.GitChangeCollectorImpl;
import com.codingreview.controlplane.workspace.change.policy.ChangePolicyEvaluatorImpl;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicy;
import com.codingreview.controlplane.legacy.worktreerun.workspace.worktree.GitWorktreeManagerImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 用真实 Git、Diff、路径门禁和 Maven 验证主工作流模块能够完整协作。 */
@Tag("legacy-worktree-run")
class WorktreeRunOrchestratorIntegrationTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @TempDir
    Path tempDir;

    @Test
    void runsRealPipelineWithFakeCodingAdapter() throws Exception {
        Path repository = createSpringBootRepository();
        Path runtimeRoot = tempDir.resolve("runtime").toAbsolutePath().normalize();
        FileRunEventJournal journal = new FileRunEventJournal(runtimeRoot, objectMapper);
        GitWorktreeManagerImpl worktreeManager = new GitWorktreeManagerImpl(runtimeRoot);
        ClaudeCodeAdapter fakeClaude = (request, eventSink) -> {
            try {
                Path generatedFile = request.workspaceRoot()
                        .resolve("src/main/java/demo/GeneratedMarker.java");
                Files.createDirectories(generatedFile.getParent());
                Files.writeString(generatedFile, """
                        package demo;

                        public final class GeneratedMarker {
                            private GeneratedMarker() {
                            }
                        }
                        """, StandardCharsets.UTF_8);
                eventSink.onEvent(new AgentEvent(
                        AgentEventType.TOOL_CALL_COMPLETED,
                        "fake-write-1",
                        objectMapper.createObjectNode()
                                .put("tool", "Write")
                                .put("path", "src/main/java/demo/GeneratedMarker.java")
                ));
                Instant now = Instant.now();
                return new ClaudeRunResult(
                        ClaudeRunStatus.COMPLETED,
                        0,
                        true,
                        false,
                        null,
                        now,
                        now,
                        null
                );
            } catch (Exception exception) {
                throw new IllegalStateException("Fake Coding Adapter failed", exception);
            }
        };
        UUID runId = UUID.randomUUID();
        WorktreeRunOrchestrator orchestrator = new WorktreeRunOrchestratorImpl(
                worktreeManager,
                fakeClaude,
                new GitChangeCollectorImpl(),
                new ChangePolicyEvaluatorImpl(),
                new MavenVerifierImpl(runtimeRoot, journal),
                journal,
                objectMapper
        );

        WorktreeRunResult result = orchestrator.execute(new WorktreeRunRequest(
                runId,
                repository,
                "HEAD",
                "生成一个可编译的标记类",
                List.of("Maven 测试通过"),
                new ChangePolicy(List.of("src/**"), List.of(".git/**"))
        ));

        assertThat(result.status()).isEqualTo(WorktreeRunStatus.COMPLETED);
        assertThat(result.changes().files())
                .extracting(file -> file.path())
                .containsExactly("src/main/java/demo/GeneratedMarker.java");
        assertThat(result.maven().passed()).isTrue();
        assertThat(result.worktree().handle().worktreePath()).exists();
        assertThat(journal.readAfter(runId.toString(), 0))
                .extracting(RunEvent::type)
                .contains(
                        RunEventType.AGENT_EVENT_OBSERVED,
                        RunEventType.PROCESS_STARTED,
                        RunEventType.ANALYSIS_COMPLETED,
                        RunEventType.RUN_COMPLETED
                );

        // 生产编排器保留现场；测试结束后显式清理，避免临时 Git 仓库残留引用。
        worktreeManager.forceCleanup(result.worktree().handle());
    }

    private Path createSpringBootRepository() throws Exception {
        Path repository = tempDir.resolve("source-repository").toAbsolutePath().normalize();
        Path source = repository.resolve("src/main/java/demo/Application.java");
        Path test = repository.resolve("src/test/java/demo/ApplicationTest.java");
        Files.createDirectories(source.getParent());
        Files.createDirectories(test.getParent());
        Files.writeString(repository.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>3.5.16</version>
                        <relativePath/>
                    </parent>
                    <groupId>demo</groupId>
                    <artifactId>workflow-fixture</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                    <properties>
                        <java.version>21</java.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-test</artifactId>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
                """, StandardCharsets.UTF_8);
        Files.writeString(source, """
                package demo;

                public class Application {
                    public static int answer() {
                        return 42;
                    }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(test, """
                package demo;

                import org.junit.jupiter.api.Test;

                import static org.assertj.core.api.Assertions.assertThat;

                class ApplicationTest {
                    @Test
                    void returnsAnswer() {
                        assertThat(Application.answer()).isEqualTo(42);
                    }
                }
                """, StandardCharsets.UTF_8);
        runGit(repository, List.of("init", "-b", "main"));
        runGit(repository, List.of("add", "."));
        runGit(repository, List.of(
                "-c", "user.name=Code Weave Test",
                "-c", "user.email=test@code-weave.local",
                "commit", "-m", "initial commit"
        ));
        return repository;
    }

    private void runGit(Path repository, List<String> arguments) throws Exception {
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
    }
}
