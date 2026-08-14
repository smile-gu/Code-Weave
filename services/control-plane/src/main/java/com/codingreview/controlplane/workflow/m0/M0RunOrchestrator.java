package com.codingreview.controlplane.workflow.m0;

import com.codingreview.controlplane.workflow.m0.model.M0RunRequest;
import com.codingreview.controlplane.workflow.m0.model.M0RunResult;

/** 顺序编排一次 M0 Coding Run 的完整主链路。 */
public interface M0RunOrchestrator {

    M0RunResult execute(M0RunRequest request);
}
