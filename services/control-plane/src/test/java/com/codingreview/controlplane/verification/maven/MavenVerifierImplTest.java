package com.codingreview.controlplane.verification.maven;

import com.codingreview.controlplane.observability.run.FileRunEventJournal;
import com.codingreview.controlplane.observability.run.RunEvent;
import com.codingreview.controlplane.observability.run.RunEventJournal;
import com.codingreview.controlplane.observability.run.RunEventType;
import com.codingreview.controlplane.observability.run.error.RunEventWriteException;
import com.codingreview.controlplane.verification.maven.model.MavenVerificationRequest;
import com.codingreview.controlplane.verification.maven.model.MavenVerificationResult;
import com.codingreview.controlplane.verification.maven.model.MavenVerificationStatus;
import com.codingreview.controlplane.verification.maven.report.SurefireReportParser;
import com.codingreview.controlplane.workspace.worktree.model.WorktreeHandle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 使用真实子进程验证 Maven 结果判定、日志证据、超时和事件闭环。 */
class MavenVerifierImplTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @TempDir
    Path tempDir;

    @Test
    void capturesLogsReportsAndLifecycleEvents() throws Exception {
        String runId = "run-success";
        Path runtimeRoot = runtimeRoot();
        FileRunEventJournal journal = new FileRunEventJournal(runtimeRoot, objectMapper);

        MavenVerificationResult result = verifier("success", journal, Duration.ofSeconds(5), 1024 * 1024)
                .verify(request(runId, createWorktree(runId)));

        assertThat(result.status()).isEqualTo(MavenVerificationStatus.PASSED);
        assertThat(result.exitCode()).isZero();
        assertThat(result.testSummary().tests()).isEqualTo(1);
        assertThat(result.testSummary().passed()).isEqualTo(1);
        assertThat(readArtifact(runtimeRoot, result.stdoutLog().relativePath())).contains("fixture stdout");
        assertThat(readArtifact(runtimeRoot, result.stderrLog().relativePath())).contains("fixture stderr");
        assertThat(result.stdoutLog().sha256()).hasSize(64);

        List<RunEventType> types = journal.readAfter(runId, 0).stream().map(RunEvent::type).toList();
        assertThat(types).startsWith(RunEventType.PROCESS_START_REQUESTED, RunEventType.PROCESS_STARTED);
        assertThat(types).contains(
                RunEventType.PROCESS_STDOUT,
                RunEventType.PROCESS_STDERR,
                RunEventType.PROCESS_EXIT_OBSERVED,
                RunEventType.LOG_CAPTURE_COMPLETED,
                RunEventType.ARTIFACT_RECORDED,
                RunEventType.ANALYSIS_COMPLETED
        );
        assertThat(types).doesNotContain(
                RunEventType.STAGE_STARTED,
                RunEventType.STAGE_COMPLETED,
                RunEventType.STAGE_FAILED
        );
    }

    @Test
    void classifiesSurefireFailuresWithoutParsingConsoleText() throws Exception {
        String runId = "run-test-failure";
        FileRunEventJournal journal = new FileRunEventJournal(runtimeRoot(), objectMapper);

        MavenVerificationResult result = verifier(
                "test-failure", journal, Duration.ofSeconds(5), 1024 * 1024
        ).verify(request(runId, createWorktree(runId)));

        assertThat(result.status()).isEqualTo(MavenVerificationStatus.TEST_FAILED);
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.testSummary().failedTests()).singleElement().satisfies(failure -> {
            assertThat(failure.testClass()).isEqualTo("DemoTest");
            assertThat(failure.testName()).isEqualTo("works");
            assertThat(failure.message()).isEqualTo("expected true");
        });
    }

    @Test
    void rejectsUnsafeSurefireReport() throws Exception {
        String runId = "run-invalid-report";
        FileRunEventJournal journal = new FileRunEventJournal(runtimeRoot(), objectMapper);

        MavenVerificationResult result = verifier(
                "invalid-report", journal, Duration.ofSeconds(5), 1024 * 1024
        ).verify(request(runId, createWorktree(runId)));

        assertThat(result.status()).isEqualTo(MavenVerificationStatus.REPORT_INVALID);
        assertThat(journal.readAfter(runId, 0))
                .filteredOn(event -> event.type() == RunEventType.RUN_NOTICE)
                .singleElement()
                .satisfies(event -> assertThat(event.payload().path("code").asText())
                        .isEqualTo("MAVEN_REPORT_INVALID"));
    }

    @Test
    void succeedsWithExplicitNoticeWhenProjectHasNoTests() throws Exception {
        String runId = "run-no-tests";
        FileRunEventJournal journal = new FileRunEventJournal(runtimeRoot(), objectMapper);

        MavenVerificationResult result = verifier(
                "no-report", journal, Duration.ofSeconds(5), 1024 * 1024
        ).verify(request(runId, createWorktree(runId)));

        assertThat(result.status()).isEqualTo(MavenVerificationStatus.PASSED);
        assertThat(result.testSummary().reportsFound()).isFalse();
        assertThat(result.userMessage()).contains("没有生成 Surefire 测试报告");
        assertThat(journal.readAfter(runId, 0))
                .filteredOn(event -> event.type() == RunEventType.RUN_NOTICE)
                .singleElement()
                .satisfies(event -> assertThat(event.payload().path("code").asText())
                        .isEqualTo("NO_SUREFIRE_REPORTS"));
    }

    @Test
    void terminatesProcessWhenCombinedLogLimitIsExceeded() throws Exception {
        String runId = "run-log-limit";
        FileRunEventJournal journal = new FileRunEventJournal(runtimeRoot(), objectMapper);
        Instant startedAt = Instant.now();

        MavenVerificationResult result = verifier(
                "large-log", journal, Duration.ofSeconds(10), 128
        ).verify(request(runId, createWorktree(runId)));

        assertThat(result.status()).isEqualTo(MavenVerificationStatus.LOG_LIMIT_EXCEEDED);
        assertThat(Duration.between(startedAt, Instant.now())).isLessThan(Duration.ofSeconds(5));
        assertThat(journal.readAfter(runId, 0)).extracting(RunEvent::type)
                .contains(RunEventType.PROCESS_TERMINATION_REQUESTED, RunEventType.PROCESS_TERMINATED);
    }

    @Test
    void terminatesChildProcessWhenMavenTimesOut() throws Exception {
        String runId = "run-timeout";
        Path worktree = createWorktree(runId);
        FileRunEventJournal journal = new FileRunEventJournal(runtimeRoot(), objectMapper);

        MavenVerificationResult result = verifier(
                "timeout-parent", journal, Duration.ofSeconds(2), 1024 * 1024
        ).verify(request(runId, worktree));

        long childPid = Long.parseLong(Files.readString(
                worktree.resolve("child.pid"), StandardCharsets.US_ASCII
        ).trim());
        assertThat(result.status()).isEqualTo(MavenVerificationStatus.TIMED_OUT);
        assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    @Test
    void stopsAndPropagatesOriginalEventJournalFailure() throws Exception {
        String runId = "run-event-failure";
        FileRunEventJournal delegate = new FileRunEventJournal(runtimeRoot(), objectMapper);
        RunEventJournal failingJournal = new FailingOutputJournal(delegate);
        MavenVerifier verifier = verifier(
                "large-log", failingJournal, Duration.ofSeconds(10), 1024 * 1024
        );
        Instant startedAt = Instant.now();

        assertThatThrownBy(() -> verifier.verify(request(runId, createWorktree(runId))))
                .isInstanceOf(RunEventWriteException.class)
                .hasMessageContaining("event journal unavailable");
        assertThat(Duration.between(startedAt, Instant.now())).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void verifiesRealSpringBootMavenFixture() {
        String runId = "run-real-maven";
        Path fixture = springBootFixture();
        FileRunEventJournal journal = new FileRunEventJournal(runtimeRoot(), objectMapper);
        MavenVerifier verifier = new MavenVerifierImpl(runtimeRoot(), journal);

        MavenVerificationResult result = verifier.verify(request(runId, fixture));

        assertThat(result.status()).isEqualTo(MavenVerificationStatus.PASSED);
        assertThat(result.exitCode()).isZero();
        assertThat(Path.of(result.command().getFirst())).isAbsolute();
        assertThat(result.testSummary().reportFiles()).isPositive();
        assertThat(result.testSummary().tests()).isPositive();
        assertThat(result.stdoutLog().bytes()).isPositive();
    }

    private MavenVerifier verifier(
            String mode,
            RunEventJournal journal,
            Duration timeout,
            long maxLogBytes
    ) throws Exception {
        List<String> commandPrefix = List.of(
                MavenProcessFixture.javaExecutable(),
                "-cp",
                MavenProcessFixture.classPath(),
                MavenProcessFixture.class.getName(),
                mode
        );
        return new MavenVerifierImpl(
                runtimeRoot(),
                journal,
                objectMapper,
                new SurefireReportParser(),
                commandPrefix,
                timeout,
                Duration.ofMillis(100),
                maxLogBytes
        );
    }

    private MavenVerificationRequest request(String runId, Path worktree) {
        return new MavenVerificationRequest(
                new WorktreeHandle(worktree, worktree, runId, "HEAD", "fixture-commit", false),
                1
        );
    }

    private Path createWorktree(String runId) throws Exception {
        Path worktree = tempDir.resolve("worktrees").resolve(runId);
        Files.createDirectories(worktree);
        Files.writeString(worktree.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        return worktree.toAbsolutePath().normalize();
    }

    private Path runtimeRoot() {
        return tempDir.resolve("runtime").toAbsolutePath().normalize();
    }

    private Path springBootFixture() {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        Path fromRepositoryRoot = workingDirectory.resolve("fixtures/spring-boot-demo");
        if (Files.isRegularFile(fromRepositoryRoot.resolve("pom.xml"))) {
            return fromRepositoryRoot;
        }
        Path fromControlPlaneModule = workingDirectory.resolve("../../fixtures/spring-boot-demo").normalize();
        if (Files.isRegularFile(fromControlPlaneModule.resolve("pom.xml"))) {
            return fromControlPlaneModule;
        }
        throw new IllegalStateException("Unable to locate fixtures/spring-boot-demo");
    }

    private String readArtifact(Path runtimeRoot, String relativePath) throws Exception {
        return Files.readString(runtimeRoot.resolve(relativePath), StandardCharsets.UTF_8);
    }

    private static final class FailingOutputJournal implements RunEventJournal {
        private final RunEventJournal delegate;

        private FailingOutputJournal(RunEventJournal delegate) {
            this.delegate = delegate;
        }

        @Override
        public RunEvent append(
                String runId,
                String stage,
                int attempt,
                RunEventType type,
                JsonNode payload
        ) {
            if (type == RunEventType.PROCESS_STDOUT) {
                throw new RunEventWriteException(
                        "event journal unavailable", "forced test failure", null
                );
            }
            return delegate.append(runId, stage, attempt, type, payload);
        }

        @Override
        public List<RunEvent> readAfter(String runId, long afterSequence) {
            return delegate.readAfter(runId, afterSequence);
        }
    }
}
