package com.codingreview.controlplane.workflow.m6;

import com.codingreview.controlplane.session.model.CodingSession;
import com.codingreview.controlplane.session.model.CodingTurn;

import java.util.function.Consumer;

/** 在持久 Session Worktree 中执行一轮 Coding 并形成质量结论。 */
public interface CodingTurnOrchestrator {
    /** 执行单轮 Claude、变更检查和 Maven 验证。 */
    CodingTurnExecution execute(
            CodingSession session,
            CodingTurn turn,
            Consumer<CodingSession> sessionUpdateSink
    );
}
