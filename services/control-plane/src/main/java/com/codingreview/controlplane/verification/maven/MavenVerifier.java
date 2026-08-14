package com.codingreview.controlplane.verification.maven;

import com.codingreview.controlplane.verification.maven.model.MavenVerificationRequest;
import com.codingreview.controlplane.verification.maven.model.MavenVerificationResult;

/** 独立执行 Maven 编译与测试，不采信 Coding Agent 的验证声明。 */
public interface MavenVerifier {

    MavenVerificationResult verify(MavenVerificationRequest request);
}
