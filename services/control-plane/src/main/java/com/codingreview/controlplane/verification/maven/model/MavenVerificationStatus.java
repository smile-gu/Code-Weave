package com.codingreview.controlplane.verification.maven.model;

/** Maven 验证的机器可判定终态。 */
public enum MavenVerificationStatus {
    PASSED,
    BUILD_FAILED,
    TEST_FAILED,
    START_FAILED,
    TIMED_OUT,
    CANCELLED,
    LOG_LIMIT_EXCEEDED,
    LOG_CAPTURE_FAILED,
    REPORT_INVALID
}
