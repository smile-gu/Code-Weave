package com.codingreview.controlplane.workflow.m0;

import com.codingreview.controlplane.workflow.m0.model.M0RunRequest;
import com.codingreview.controlplane.workflow.m0.model.M0RunResult;

/**
 * 顺序编排一次 M0 Coding Run 的五阶段业务主链路。
 * 调用方负责在执行前发布 Run 的创建、排队和开始事件。
 */
public interface M0RunOrchestrator {

    M0RunResult execute(M0RunRequest request);
}
