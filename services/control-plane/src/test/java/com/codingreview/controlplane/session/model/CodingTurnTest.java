package com.codingreview.controlplane.session.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证 Turn 生命周期和质量结论保持独立且时间事实准确。 */
class CodingTurnTest {

    @Test
    void runningTurnHasNoEndedAtAndVerifiedTurnKeepsFingerprint() {
        Instant createdAt = Instant.parse("2026-08-18T08:00:00Z");
        CodingTurn queued = CodingTurn.queued(
                UUID.randomUUID(), UUID.randomUUID(), 1, "实现接口", createdAt
        );

        CodingTurn running = queued.running(createdAt.plusSeconds(1));
        CodingTurn completed = running.complete(
                TurnOutcome.CHANGES_VERIFIED, 2, "sha256:verified",
                "验证通过", "已完成", "claude-session", createdAt.plusSeconds(2)
        );

        assertThat(running.endedAt()).isNull();
        assertThat(completed.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(completed.outcome()).isEqualTo(TurnOutcome.CHANGES_VERIFIED);
        assertThat(completed.workspaceFingerprint()).isEqualTo("sha256:verified");
    }

    @Test
    void rejectsVerifiedTurnWithoutWorkspaceFingerprint() {
        CodingTurn running = CodingTurn.queued(
                UUID.randomUUID(), UUID.randomUUID(), 1, "实现接口", Instant.now()
        ).running(Instant.now().plusMillis(1));

        assertThatThrownBy(() -> running.complete(
                TurnOutcome.CHANGES_VERIFIED, 1, null,
                "验证通过", null, null, Instant.now().plusSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspaceFingerprint");
    }
    @Test
    void handoffProvenanceSurvivesEveryLifecycleAndDiagnosticTransition() {
        UUID source = UUID.randomUUID(); Instant now = Instant.now();
        var queued = CodingTurn.queued(UUID.randomUUID(), UUID.randomUUID(), 1, "authorized data", now).withSourceHandoff(source);
        var running = queued.running(now.plusSeconds(1));
        var states = java.util.List.of(queued, running, queued.failBeforeStart("QUEUE_FAILED", "failed", now.plusSeconds(2)),
                queued.cancelled("cancelled", now.plusSeconds(2)), queued.interrupted(now.plusSeconds(2)),
                running.cancelled("cancelled", now.plusSeconds(2)), running.interrupted(now.plusSeconds(2)),
                running.failed(TurnOutcome.AGENT_FAILED, "FAILED", "failed", null, null, now.plusSeconds(2)),
                running.complete(TurnOutcome.CHANGES_VERIFIED, 1, "sha256:content", "done", "reply", null, now.plusSeconds(2)),
                running.withDiagnostic("AGENT", "FAILED", "FAILED"));
        assertThat(states).allSatisfy(turn -> assertThat(turn.sourceHandoffId()).isEqualTo(source));
    }
}
