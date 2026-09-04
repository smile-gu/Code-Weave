package com.codingreview.controlplane.application.session;

import com.codingreview.controlplane.observability.run.RunEventJournal;
import com.codingreview.controlplane.session.model.CodingSession;
import com.codingreview.controlplane.session.model.CodingTurn;
import com.codingreview.controlplane.session.model.TurnStatus;
import com.codingreview.controlplane.session.persistence.CodingSessionRepository;
import com.codingreview.controlplane.session.persistence.CodingTurnRepository;
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
