package com.codingreview.controlplane.api.session.dto;

import com.codingreview.controlplane.session.model.CodingTurn;
import com.codingreview.controlplane.session.model.TurnOutcome;
import com.codingreview.controlplane.session.model.TurnStatus;

import java.time.Instant;
import java.util.UUID;

/** 面向前端的单轮 Coding 摘要。 */
public record CodingTurnResponse(
        UUID turnId, UUID sessionId, int sequence, String userPrompt,
        TurnStatus status, TurnOutcome outcome, String assistantMessage,
        int changedFiles, String workspaceFingerprint, String failureCode, String userMessage,
        Instant createdAt, Instant startedAt, Instant endedAt, String failedStage, String failureKind
) {
    /** 将领域 Turn 转成稳定 API 响应。 */
    public static CodingTurnResponse from(CodingTurn turn) {
        return new CodingTurnResponse(turn.turnId(), turn.sessionId(), turn.sequence(), turn.userPrompt(),
                turn.status(), turn.outcome(), turn.assistantMessage(), turn.changedFiles(),
                turn.workspaceFingerprint(), turn.failureCode(),
                turn.userMessage(), turn.createdAt(), turn.startedAt(), turn.endedAt(), turn.failedStage(), turn.failureKind());
    }
}
