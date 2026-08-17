package com.codingreview.controlplane.api.run.dto.result;

import com.codingreview.controlplane.adapter.claude.runtime.ClaudeRunResult;
import com.codingreview.controlplane.adapter.claude.runtime.ClaudeRunStatus;
import com.codingreview.controlplane.verification.maven.model.MavenTestFailure;
import com.codingreview.controlplane.verification.maven.model.MavenTestSummary;
import com.codingreview.controlplane.verification.maven.model.MavenVerificationResult;
import com.codingreview.controlplane.verification.maven.model.MavenVerificationStatus;
import com.codingreview.controlplane.workflow.m0.model.M0RunResult;
import com.codingreview.controlplane.workflow.m0.model.M0RunStatus;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicyResult;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicyViolation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 面向用户的完整业务结论，主动排除命令、诊断和内部事件对象。 */
public record RunResultResponse(
        UUID runId,
        M0RunStatus status,
        Instant startedAt,
        Instant endedAt,
        String failedStage,
        String failureCode,
        String message,
        String worktree,
        boolean changesAvailable,
        Integer changedFiles,
        CodingSummary coding,
        PolicySummary changePolicy,
        MavenSummary maven
) {
    /** 将 M0 领域结果映射为不含内部诊断的接口响应。 */
    public static RunResultResponse from(M0RunResult result) {
        return new RunResultResponse(
                result.runId(),
                result.status(),
                result.startedAt(),
                result.endedAt(),
                result.failedStage() == null ? null : result.failedStage().name(),
                result.failureCode() == null ? null : result.failureCode().name(),
                result.userMessage(),
                result.worktree() == null
                        ? null : result.worktree().handle().worktreePath().toString(),
                result.changes() != null,
                result.changes() == null ? null : result.changes().files().size(),
                CodingSummary.from(result.claude()),
                PolicySummary.from(result.changePolicy()),
                MavenSummary.from(result.maven())
        );
    }

    public record CodingSummary(
            ClaudeRunStatus status,
            Integer exitCode,
            boolean initialized,
            boolean terminalResultReceived,
            Instant startedAt,
            Instant endedAt
    ) {
        /** 提取 Claude Code 的公开运行摘要。 */
        private static CodingSummary from(ClaudeRunResult result) {
            return result == null ? null : new CodingSummary(
                    result.status(),
                    result.exitCode(),
                    result.initialized(),
                    result.terminalResultReceived(),
                    result.startedAt(),
                    result.endedAt()
            );
        }
    }

    public record PolicySummary(
            boolean compliant,
            List<PolicyViolationSummary> violations
    ) {
        public PolicySummary {
            violations = List.copyOf(violations);
        }

        /** 提取路径策略结论和违规详情。 */
        private static PolicySummary from(ChangePolicyResult result) {
            return result == null ? null : new PolicySummary(
                    result.compliant(),
                    result.violations().stream().map(PolicyViolationSummary::from).toList()
            );
        }
    }

    public record PolicyViolationSummary(
            String path,
            String changeType,
            String violationType,
            String matchedRule,
            String message
    ) {
        /** 将单条路径策略违规映射为公开字段。 */
        private static PolicyViolationSummary from(ChangePolicyViolation violation) {
            return new PolicyViolationSummary(
                    violation.path(),
                    violation.changeType().name(),
                    violation.violationType().name(),
                    violation.matchedRule(),
                    violation.message()
            );
        }
    }

    public record MavenSummary(
            MavenVerificationStatus status,
            Integer exitCode,
            Instant startedAt,
            Instant endedAt,
            String message,
            TestSummary tests
    ) {
        /** 提取 Maven 验证结论和测试摘要。 */
        private static MavenSummary from(MavenVerificationResult result) {
            return result == null ? null : new MavenSummary(
                    result.status(),
                    result.exitCode(),
                    result.startedAt(),
                    result.endedAt(),
                    result.userMessage(),
                    TestSummary.from(result.testSummary())
            );
        }
    }

    public record TestSummary(
            int reportFiles,
            long total,
            long passed,
            long failures,
            long errors,
            long skipped,
            double durationSeconds,
            List<TestFailureSummary> failedTests,
            boolean failureDetailsTruncated
    ) {
        public TestSummary {
            failedTests = List.copyOf(failedTests);
        }

        /** 将 Surefire 汇总映射为前端测试统计。 */
        private static TestSummary from(MavenTestSummary summary) {
            return summary == null ? null : new TestSummary(
                    summary.reportFiles(),
                    summary.tests(),
                    summary.passed(),
                    summary.failures(),
                    summary.errors(),
                    summary.skipped(),
                    summary.durationSeconds(),
                    summary.failedTests().stream().map(TestFailureSummary::from).toList(),
                    summary.failureDetailsTruncated()
            );
        }
    }

    public record TestFailureSummary(
            String testClass,
            String testName,
            String type,
            String message
    ) {
        /** 将单个失败测试映射为公开字段。 */
        private static TestFailureSummary from(MavenTestFailure failure) {
            return new TestFailureSummary(
                    failure.testClass(),
                    failure.testName(),
                    failure.type(),
                    failure.message()
            );
        }
    }
}
