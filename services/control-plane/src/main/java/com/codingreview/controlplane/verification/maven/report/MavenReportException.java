package com.codingreview.controlplane.verification.maven.report;

/** Surefire XML 无法被可信解析时抛出的异常。 */
public class MavenReportException extends RuntimeException {

    public MavenReportException(String message, Throwable cause) {
        super(message, cause);
    }
}
