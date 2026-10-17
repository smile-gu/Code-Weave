package com.codingreview.controlplane.verification.maven;

import com.codingreview.controlplane.verification.maven.model.MavenVerificationRequest;
import com.codingreview.controlplane.verification.maven.model.MavenVerificationResult;

/** 独立执行 Maven 编译与测试，不采信 Coding Agent 的验证声明。 */
public interface MavenVerifier {

    /** 在指定工作区中独立执行 Maven 编译与测试验证。 */
    MavenVerificationResult verify(MavenVerificationRequest request);
}
