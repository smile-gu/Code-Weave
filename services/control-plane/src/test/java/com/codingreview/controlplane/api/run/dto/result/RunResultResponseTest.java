package com.codingreview.controlplane.api.run.dto.result;

import com.codingreview.controlplane.adapter.claude.runtime.ClaudeRunResult;
import com.codingreview.controlplane.adapter.claude.runtime.ClaudeRunStatus;
import com.codingreview.controlplane.verification.maven.model.MavenTestFailure;
import com.codingreview.controlplane.verification.maven.model.MavenTestSummary;
import com.codingreview.controlplane.verification.maven.model.MavenVerificationResult;
import com.codingreview.controlplane.verification.maven.model.MavenVerificationStatus;
import com.codingreview.controlplane.workflow.m0.model.M0RunFailureCode;
import com.codingreview.controlplane.workflow.m0.model.M0RunResult;
import com.codingreview.controlplane.workflow.m0.model.M0RunStage;
import com.codingreview.controlplane.workflow.m0.model.M0RunStatus;
import com.codingreview.controlplane.workspace.change.model.GitChangeSet;
import com.codingreview.controlplane.workspace.change.model.GitChangeType;
import com.codingreview.controlplane.workspace.change.model.GitChangedFile;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicyResult;
import com.codingreview.controlplane.workspace.worktree.model.WorktreeCreateResult;
import com.codingreview.controlplane.workspace.worktree.model.WorktreeHandle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RunResultResponseTest {

    @Test
    void mapsStructuredEvidenceWithoutExposingCommandsOrDiagnostics() {
        UUID runId = UUID.randomUUID();
        Instant startedAt = Instant.parse("2026-08-17T03:00:00Z");
        Path worktreePath = Path.of("D:/runtime/worktrees/" + runId);
        WorktreeHandle handle = new WorktreeHandle(
                Path.of("D:/repository"),
                worktreePath,
                runId.toString(),
                "HEAD",
                "abc123",
                false
        );
        ClaudeRunResult claude = new ClaudeRunResult(
                ClaudeRunStatus.COMPLETED,
                0,
                true,
                false,
                null,
                startedAt,
                startedAt.plusSeconds(2),
                "不得暴露的 Claude 诊断"
        );
        GitChangeSet changes = new GitChangeSet(
                "abc123",
                worktreePath,
                List.of(new GitChangedFile(
                        "src/main/java/demo/UserService.java",
                        null,
                        GitChangeType.MODIFIED,
                        false
                )),
                "diff --git sample",
                18
        );
        MavenTestSummary testSummary = new MavenTestSummary(
                1,
                2,
                1,
                0,
                0,
                0.25,
                List.of(new MavenTestFailure(
                        "demo.UserServiceTest",
                        "rejectsInvalidUser",
                        "AssertionError",
                        "expected validation failure"
                )),
                false
        );
        MavenVerificationResult maven = new MavenVerificationResult(
                MavenVerificationStatus.TEST_FAILED,
                1,
                List.of("mvn", "-Dsecret=value", "test"),
                startedAt.plusSeconds(3),
                startedAt.plusSeconds(5),
                null,
                null,
                testSummary,
                "Maven 测试未通过。",
                "不得暴露的 Maven 诊断"
        );
        M0RunResult result = new M0RunResult(
                runId,
                M0RunStatus.FAILED,
                M0RunStage.MAVEN,
                M0RunFailureCode.MAVEN_FAILED,
                new WorktreeCreateResult(handle, List.of()),
                claude,
                changes,
                new ChangePolicyResult(List.of()),
                maven,
                startedAt,
                startedAt.plusSeconds(5),
                "Maven 测试未通过。",
                "不得暴露的 Run 诊断"
        );

        RunResultResponse response = RunResultResponse.from(result);
        JsonNode json = JsonMapper.builder().findAndAddModules().build().valueToTree(response);

        assertThat(response.coding().status()).isEqualTo(ClaudeRunStatus.COMPLETED);
        assertThat(response.changePolicy().compliant()).isTrue();
        assertThat(response.maven().tests().total()).isEqualTo(2);
        assertThat(response.maven().tests().passed()).isEqualTo(1);
        assertThat(response.maven().tests().failedTests()).hasSize(1);
        assertThat(json.findValues("diagnostic")).isEmpty();
        assertThat(json.findValues("command")).isEmpty();
        assertThat(json.toString()).doesNotContain("-Dsecret=value", "不得暴露");
    }
}
