package com.codingreview.controlplane.verification.maven.runtime;

/** Maven 进程和原始日志采集层的终态。 */
public enum MavenProcessStatus {
    COMPLETED,
    START_FAILED,
    TIMED_OUT,
    CANCELLED,
    LOG_LIMIT_EXCEEDED,
    LOG_CAPTURE_FAILED
}
