package com.codingreview.controlplane.application.session;

import com.codingreview.controlplane.session.model.*;
import com.codingreview.controlplane.session.persistence.*;
import com.codingreview.controlplane.session.guard.SessionWorkspaceGuard;
import com.codingreview.controlplane.workflow.coding.CodingTurnOrchestrator;
import com.codingreview.controlplane.workspace.change.GitChangeCollector;
import com.codingreview.controlplane.observability.run.RunEventJournal;
import com.codingreview.controlplane.runtime.readiness.RuntimeReadinessService;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

/** 直接验证正式 Turn 服务的关闭安全门禁，不用替身模拟门禁结论。 */
class CodingSessionCloseGateTest {
    final UUID id = UUID.randomUUID();
    final SessionOperationLockManager locks = new SessionOperationLockManager();
    final CodingTurnRepository turns = mock(CodingTurnRepository.class);
    final CodingTurnApplicationService service = new CodingTurnApplicationService(
            mock(CodingSessionRepository.class), turns, mock(CodingTurnOrchestrator.class),
            mock(GitChangeCollector.class), mock(RunEventJournal.class), JsonMapper.builder().build(),
            task -> {}, mock(RuntimeReadinessService.class), mock(SessionWorkspaceGuard.class), locks);

    @Test void refusesQueuedAndRunningHistory() {
        var queued = CodingTurn.queued(UUID.randomUUID(), id, 1, "task", Instant.now());
        for (var turn : List.of(queued, queued.running(Instant.now()))) {
            when(turns.findAll(id)).thenReturn(List.of(turn));
            synchronized (locks.lock(id)) {
                assertThatThrownBy(() -> service.assertIdleAndSafeForClose(id))
                        .extracting("code").isEqualTo("TURN_IN_PROGRESS");
            }
        }
    }

    @Test void refusesCleanupEvidenceEvenWithoutMemoryHandle() {
        var failed = CodingTurn.queued(UUID.randomUUID(), id, 1, "task", Instant.now())
                .running(Instant.now()).failed(TurnOutcome.AGENT_FAILED, "PROCESS_CLEANUP_FAILED",
                        "cleanup", null, null, Instant.now());
        when(turns.findAll(id)).thenReturn(List.of(failed));
        synchronized (locks.lock(id)) {
            assertThatThrownBy(() -> service.assertIdleAndSafeForClose(id))
                    .extracting("code").isEqualTo("SESSION_EXECUTION_BLOCKED");
        }
    }

    @Test void unreadableHistoryIsNeverTreatedAsEmpty() {
        var failure = new IllegalStateException("unreadable history");
        when(turns.findAll(id)).thenThrow(failure);
        synchronized (locks.lock(id)) {
            assertThatThrownBy(() -> service.assertIdleAndSafeForClose(id)).isSameAs(failure);
        }
    }

    @Test void requiresCallerToHoldSharedOperationLock() {
        assertThatThrownBy(() -> service.assertIdleAndSafeForClose(id)).isInstanceOf(IllegalStateException.class);
    }
}
