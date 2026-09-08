package com.codingreview.controlplane.application.session;

import com.codingreview.controlplane.observability.run.RunEventJournal;
import com.codingreview.controlplane.session.model.CodingSession;
import com.codingreview.controlplane.session.model.CodingTurn;
import com.codingreview.controlplane.session.model.TurnStatus;
import com.codingreview.controlplane.session.persistence.CodingSessionRepository;
import com.codingreview.controlplane.session.persistence.CodingTurnRepository;
import com.codingreview.controlplane.session.persistence.CodingTurnHistoryException;
import com.codingreview.controlplane.session.persistence.CodingTurnHistoryFailureKind;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicy;
import com.codingreview.controlplane.workspace.git.model.GitRepositorySnapshot;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyInt;

/** 验证服务重启会把遗留的排队和运行中 Turn 标为可追溯中断。 */
class CodingTurnRecoveryServiceTest {

    @Test
    void interruptsQueuedAndRunningTurns() {
        CodingSessionRepository sessions = mock(CodingSessionRepository.class);
        CodingTurnRepository turns = mock(CodingTurnRepository.class);
        RunEventJournal journal = mock(RunEventJournal.class);
        CodingSession session = activeSession();
        CodingTurn queued = CodingTurn.queued(
                UUID.randomUUID(), session.sessionId(), 1, "排队任务", Instant.now()
        );
        CodingTurn running = CodingTurn.queued(
                UUID.randomUUID(), session.sessionId(), 2, "执行任务", Instant.now()
        ).running(Instant.now().plusMillis(1));
        when(sessions.findAll()).thenReturn(List.of(session));
        when(turns.findAll(session.sessionId())).thenReturn(List.of(queued, running));

        new CodingTurnRecoveryService(
                sessions, turns, journal,
                JsonMapper.builder().findAndAddModules().build()
        ).run(null);

        verify(turns, times(2)).save(any(CodingTurn.class));
        verify(journal, times(2)).append(any(), any(), anyInt(), any(), any());
        org.mockito.ArgumentCaptor<CodingTurn> captured =
                org.mockito.ArgumentCaptor.forClass(CodingTurn.class);
        verify(turns, times(2)).save(captured.capture());
        assertThat(captured.getAllValues())
                .allMatch(turn -> turn.status() == TurnStatus.INTERRUPTED)
                .allMatch(turn -> turn.failureCode().equals("SERVICE_RESTARTED"));
    }

    @Test
    void isolatesDamagedSessionAndContinuesRecoveringOtherSessions() {
        CodingSessionRepository sessions = mock(CodingSessionRepository.class);
        CodingTurnRepository turns = mock(CodingTurnRepository.class);
        RunEventJournal journal = mock(RunEventJournal.class);
        CodingSession damaged = activeSession();
        CodingSession healthy = activeSession();
        CodingTurn queued = CodingTurn.queued(
                UUID.randomUUID(), healthy.sessionId(), 1, "正常遗留任务", Instant.now()
        );
        when(sessions.findAll()).thenReturn(List.of(damaged, healthy));
        when(turns.findAll(damaged.sessionId())).thenThrow(new CodingTurnHistoryException(
                damaged.sessionId(), Path.of("D:/runtime/damaged.json"),
                CodingTurnHistoryFailureKind.DATA_CORRUPTED,
                "damaged", null
        ));
        when(turns.findAll(healthy.sessionId())).thenReturn(List.of(queued));

        new CodingTurnRecoveryService(
                sessions, turns, journal,
                JsonMapper.builder().findAndAddModules().build()
        ).run(null);

        verify(sessions).save(org.mockito.ArgumentMatchers.argThat(session ->
                session.sessionId().equals(damaged.sessionId())
                        && "TURN_HISTORY_CORRUPTED".equals(session.failureCode())));
        verify(turns).save(org.mockito.ArgumentMatchers.argThat(turn ->
                turn.turnId().equals(queued.turnId()) && turn.status() == TurnStatus.INTERRUPTED));
    }

    @Test
    void keepsExistingTerminalWithoutRecreatingMissingNotification() {
        CodingSessionRepository sessions = mock(CodingSessionRepository.class);
        CodingTurnRepository turns = mock(CodingTurnRepository.class);
        RunEventJournal journal = mock(RunEventJournal.class);
        CodingSession session = activeSession();
        CodingTurn completed = CodingTurn.queued(
                UUID.randomUUID(), session.sessionId(), 1, "已完成", Instant.now()
        ).running(Instant.now().plusMillis(1)).complete(
                com.codingreview.controlplane.session.model.TurnOutcome.NO_CHANGES,
                0, null, "完成", "完成", null, Instant.now().plusMillis(2)
        );
        when(sessions.findAll()).thenReturn(List.of(session));
        when(turns.findAll(session.sessionId())).thenReturn(List.of(completed));

        new CodingTurnRecoveryService(
                sessions, turns, journal,
                JsonMapper.builder().findAndAddModules().build()
        ).run(null);

        verify(turns, never()).save(any());
        verify(journal, never()).append(any(), any(), anyInt(), any(), any());
    }

    @Test
    void notificationFailureForOneSessionDoesNotStopOtherRecovery() {
        CodingSessionRepository sessions = mock(CodingSessionRepository.class);
        CodingTurnRepository turns = mock(CodingTurnRepository.class);
        RunEventJournal journal = mock(RunEventJournal.class);
        CodingSession first = activeSession();
        CodingSession second = activeSession();
        CodingTurn firstTurn = CodingTurn.queued(
                UUID.randomUUID(), first.sessionId(), 1, "第一会话", Instant.now()
        );
        CodingTurn secondTurn = CodingTurn.queued(
                UUID.randomUUID(), second.sessionId(), 1, "第二会话", Instant.now()
        );
        when(sessions.findAll()).thenReturn(List.of(first, second));
        when(turns.findAll(first.sessionId())).thenReturn(List.of(firstTurn));
        when(turns.findAll(second.sessionId())).thenReturn(List.of(secondTurn));
        org.mockito.Mockito.doThrow(new IllegalStateException("notification failed"))
                .doReturn(null)
                .when(journal).append(any(), any(), anyInt(), any(), any());

        new CodingTurnRecoveryService(
                sessions, turns, journal,
                JsonMapper.builder().findAndAddModules().build()
        ).run(null);

        verify(turns, org.mockito.Mockito.times(2)).save(
                org.mockito.ArgumentMatchers.argThat(turn -> turn.status() == TurnStatus.INTERRUPTED)
        );
    }

    /** 创建直接绑定真实仓库的测试 Session。 */
    private CodingSession activeSession() {
        UUID sessionId = UUID.randomUUID();
        Path repository = Path.of("D:/test/repository").toAbsolutePath();
        Instant now = Instant.parse("2026-08-18T07:59:00Z");
        return CodingSession.initializing(
                sessionId, "测试会话", repository, "HEAD",
                new ChangePolicy(List.of("src/**"), List.of(".git/**")), now
        ).activateRealRepository(new GitRepositorySnapshot(
                repository, "main", "abc123", false
        ), now.plusSeconds(1));
    }
}
