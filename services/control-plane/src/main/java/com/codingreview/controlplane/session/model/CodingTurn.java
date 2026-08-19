package com.codingreview.controlplane.session.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 表示 Coding Session 中一次可恢复、可追溯的用户消息执行。 */
public record CodingTurn(
        UUID turnId,
        UUID sessionId,
        int sequence,
        String userPrompt,
        TurnStatus status,
        TurnOutcome outcome,
        String assistantMessage,
        String nativeClaudeSessionId,
        int changedFiles,
        String workspaceFingerprint,
        String failureCode,
        String userMessage,
        Instant createdAt,
        Instant startedAt,
        Instant endedAt
) {
    public CodingTurn {
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(sessionId, "sessionId");
        if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
        if (userPrompt == null || userPrompt.isBlank()) throw new IllegalArgumentException("userPrompt must not be blank");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        if (changedFiles < 0) throw new IllegalArgumentException("changedFiles must not be negative");
        if (outcome == TurnOutcome.CHANGES_VERIFIED
                && (workspaceFingerprint == null || workspaceFingerprint.isBlank())) {
            throw new IllegalArgumentException("verified turn requires workspaceFingerprint");
        }
        if (startedAt != null && startedAt.isBefore(createdAt)) throw new IllegalArgumentException("startedAt before createdAt");
        if (endedAt != null && startedAt != null && endedAt.isBefore(startedAt)) throw new IllegalArgumentException("endedAt before startedAt");
    }

    /** 创建进入队列的 Turn。 */
    public static CodingTurn queued(UUID turnId, UUID sessionId, int sequence, String prompt, Instant now) {
        return new CodingTurn(turnId, sessionId, sequence, prompt, TurnStatus.QUEUED, null,
                null, null, 0, null, null, "任务已进入 Coding 队列。", now, null, null);
    }

    /** 将排队 Turn 推进为运行中。 */
    public CodingTurn running(Instant now) {
        requireStatus(TurnStatus.QUEUED);
        return new CodingTurn(turnId, sessionId, sequence, userPrompt, TurnStatus.RUNNING, null,
                null, nativeClaudeSessionId, 0, null, null,
                "Coding Agent 正在执行。", createdAt, now, null);
    }

    /** 记录 Turn 的正常业务结论。 */
    public CodingTurn complete(TurnOutcome result, int files, String fingerprint, String message,
                               String assistant, String nativeId, Instant now) {
        if (status != TurnStatus.RUNNING) throw new IllegalStateException("only running turn can complete");
        return new CodingTurn(turnId, sessionId, sequence, userPrompt, TurnStatus.COMPLETED, result,
                assistant, nativeId, files, fingerprint, null, message, createdAt, startedAt, now);
    }

    /** 记录 Agent 或基础设施失败。 */
    public CodingTurn failed(TurnOutcome result, String code, String message, String assistant, String nativeId, Instant now) {
        if (status != TurnStatus.RUNNING) throw new IllegalStateException("only running turn can fail");
        return new CodingTurn(turnId, sessionId, sequence, userPrompt, TurnStatus.FAILED, result,
                assistant, nativeId, changedFiles, workspaceFingerprint, code, message,
                createdAt, startedAt, now);
    }

    /** 记录用户主动停止的 Turn。 */
    public CodingTurn cancelled(String message, Instant now) {
        if (status != TurnStatus.QUEUED && status != TurnStatus.RUNNING) throw new IllegalStateException("turn is not cancellable");
        return new CodingTurn(turnId, sessionId, sequence, userPrompt, TurnStatus.CANCELLED, null,
                assistantMessage, nativeClaudeSessionId, changedFiles, workspaceFingerprint,
                "CANCELLED", message,
                createdAt, startedAt, now);
    }

    /** 服务重启后把无法确认结果的运行中 Turn 标记为中断。 */
    public CodingTurn interrupted(Instant now) {
        if (status != TurnStatus.QUEUED && status != TurnStatus.RUNNING) {
            throw new IllegalStateException("only queued or running turn can be interrupted");
        }
        return new CodingTurn(turnId, sessionId, sequence, userPrompt, TurnStatus.INTERRUPTED, null,
                assistantMessage, nativeClaudeSessionId, changedFiles, workspaceFingerprint,
                "SERVICE_RESTARTED",
                "服务在本轮完成前重启，结果无法确认。", createdAt, startedAt, now);
    }

    /** 检查状态转换的前置条件。 */
    private void requireStatus(TurnStatus expected) {
        if (status != expected) throw new IllegalStateException("invalid turn state: " + status);
    }
}
