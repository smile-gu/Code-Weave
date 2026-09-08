package com.codingreview.controlplane.workflow.coding;

import com.codingreview.controlplane.session.model.CodingSession;
import com.codingreview.controlplane.session.model.CodingTurn;

import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

/** 在 Session 绑定的真实工作区中执行一轮 Coding 并形成质量结论。 */
public interface CodingTurnOrchestrator {
    /** 执行单轮 Claude、变更检查和 Maven 验证。 */
    CodingTurnExecution execute(
            CodingSession session,
            CodingTurn turn,
            Consumer<CodingSession> sessionUpdateSink
    );

    /** 执行单轮 Coding，并在阶段边界响应应用层传入的取消请求。 */
    default CodingTurnExecution execute(
            CodingSession session,
            CodingTurn turn,
            Consumer<CodingSession> sessionUpdateSink,
            BooleanSupplier cancellationRequested
    ) {
        return execute(session, turn, sessionUpdateSink);
    }
}
