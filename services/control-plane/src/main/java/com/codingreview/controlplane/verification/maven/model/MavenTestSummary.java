package com.codingreview.controlplane.verification.maven.model;

import java.util.List;

/** 多模块 Surefire XML 汇总结果。 */
public record MavenTestSummary(
        int reportFiles,
        long tests,
        long failures,
        long errors,
        long skipped,
        double durationSeconds,
        List<MavenTestFailure> failedTests,
        boolean failureDetailsTruncated
) {
    public MavenTestSummary {
        if (reportFiles < 0 || tests < 0 || failures < 0 || errors < 0 || skipped < 0
                || durationSeconds < 0) {
            throw new IllegalArgumentException("test summary counters must not be negative");
        }
        if (failures + errors + skipped > tests) {
            throw new IllegalArgumentException("test result counters exceed total tests");
        }
        failedTests = List.copyOf(failedTests);
    }

    /** 判断本次验证是否发现了 Surefire 报告。 */
    public boolean reportsFound() {
        return reportFiles > 0;
    }

    /** 计算成功通过且未跳过的测试数量。 */
    public long passed() {
        return tests - failures - errors - skipped;
    }

    /** 判断测试报告中是否包含失败或错误。 */
    public boolean hasTestFailures() {
        return failures > 0 || errors > 0;
    }
}
