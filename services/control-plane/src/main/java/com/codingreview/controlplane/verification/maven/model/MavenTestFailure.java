package com.codingreview.controlplane.verification.maven.model;

/** Surefire 报告中的单个失败或错误测试。 */
public record MavenTestFailure(
        String testClass,
        String testName,
        String type,
        String message
) {
}
