package com.codingreview.controlplane.legacy.worktreerun.workflow;

import com.codingreview.controlplane.legacy.worktreerun.workflow.model.WorktreeRunRequest;
import com.codingreview.controlplane.legacy.worktreerun.workflow.model.WorktreeRunResult;

/**
 * 顺序编排一次 旧版 Worktree Run 的五阶段业务主链路。
 * 调用方负责在执行前发布 Run 的创建、排队和开始事件。
 */
public interface WorktreeRunOrchestrator {

    /** 按固定顺序执行五阶段主链路并返回最终业务结果。 */
    WorktreeRunResult execute(WorktreeRunRequest request);
}
