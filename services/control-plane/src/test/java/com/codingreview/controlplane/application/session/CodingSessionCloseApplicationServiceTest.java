package com.codingreview.controlplane.application.session;

import com.codingreview.controlplane.application.session.error.*;
import com.codingreview.controlplane.session.model.*;
import com.codingreview.controlplane.session.persistence.*;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicy;
import com.codingreview.controlplane.workspace.git.model.GitRepositorySnapshot;
import com.codingreview.controlplane.workspace.lock.RepositoryWriteLockManager;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** 验证关闭的保存顺序、部分成功、互斥与幂等补偿。 */
class CodingSessionCloseApplicationServiceTest {
    final CodingSessionRepository repository = mock(CodingSessionRepository.class);
    final CodingTurnApplicationService turns = mock(CodingTurnApplicationService.class);
    final RepositoryWriteLockManager locks = mock(RepositoryWriteLockManager.class);
    final SessionOperationLockManager operations = new SessionOperationLockManager();
    final CodingSessionCloseApplicationService service =
            new CodingSessionCloseApplicationService(repository, turns, operations, locks, ReviewTestSupport.emptyGuard());
    final CodingSession initial = CodingSession.initializing(UUID.randomUUID(), "关闭测试",
            Path.of("D:/repository"), "main", new ChangePolicy(List.of(), List.of()), Instant.now())
            .activateRealRepository(new GitRepositorySnapshot(Path.of("D:/repository"), "main", "abc", true), Instant.now());
    final AtomicReference<CodingSession> stored = new AtomicReference<>(initial);

    CodingSessionCloseApplicationServiceTest() {
        when(repository.find(initial.sessionId())).thenAnswer(call -> Optional.of(stored.get()));
        doAnswer(call -> { stored.set(call.getArgument(0)); return null; }).when(repository).save(any());
    }

    @Test void savesBeforeReleaseAndPreservesContextOnRepeat() {
        doAnswer(call -> {
            assertThat(stored.get().status()).isEqualTo(CodingSessionStatus.CLOSED);
            assertThat(Thread.holdsLock(operations.lock(initial.sessionId()))).isTrue();
            return null;
        }).when(locks).release(initial.sourceRepository(), initial.sessionId());
        var closed = service.close(initial.sessionId());
        assertThat(service.close(initial.sessionId())).isEqualTo(closed);
        assertThat(closed.closedAt()).isNotNull();
        assertThat(closed.sourceRepositoryDirty()).isTrue();
        assertThat(closed.activeReviewBaselineCommit()).isEqualTo(initial.activeReviewBaselineCommit());
        verify(repository, times(1)).save(any());
        verify(turns, times(1)).assertIdleAndSafeForClose(initial.sessionId());
    }

    @Test void writeBeforeFailureDoesNotRelease() {
        doThrow(new IllegalStateException("before")).when(repository).save(any());
        assertThatThrownBy(() -> service.close(initial.sessionId()))
                .isInstanceOf(CodingSessionCloseException.class)
                .extracting("code").isEqualTo("SESSION_CLOSE_STATE_WRITE_FAILED");
        verifyNoInteractions(locks);
        assertThat(stored.get()).isEqualTo(initial);
    }

    @Test void writeAfterFailureContinuesOnlyAfterExactReadback() {
        doAnswer(call -> { stored.set(call.getArgument(0)); throw new IllegalStateException("after"); })
                .when(repository).save(any());
        assertThat(service.close(initial.sessionId()).status()).isEqualTo(CodingSessionStatus.CLOSED);
        verify(locks).release(initial.sourceRepository(), initial.sessionId());
    }

    @Test void unreadableReadbackKeepsProtection() {
        doAnswer(call -> {
            when(repository.find(initial.sessionId())).thenThrow(new IllegalStateException("read"));
            throw new IllegalStateException("write");
        }).when(repository).save(any());
        assertThatThrownBy(() -> service.close(initial.sessionId())).isInstanceOf(CodingSessionCloseException.class);
        verifyNoInteractions(locks);
    }

    @Test void releaseFailureKeepsClosedAndRetriesWithoutRewriting() {
        doThrow(new IllegalStateException("release")).doNothing().when(locks)
                .release(initial.sourceRepository(), initial.sessionId());
        assertThatThrownBy(() -> service.close(initial.sessionId()))
                .extracting("code").isEqualTo("SESSION_CLOSE_RELEASE_PENDING");
        var closed = stored.get();
        assertThat(closed.status()).isEqualTo(CodingSessionStatus.CLOSED);
        assertThat(service.close(initial.sessionId())).isEqualTo(closed);
        verify(repository, times(1)).save(any());
    }

    @Test void gateFailureNeverWritesOrReleases() {
        doThrow(new CodingSessionConflictException("TURN_IN_PROGRESS", "busy"))
                .when(turns).assertIdleAndSafeForClose(initial.sessionId());
        assertThatThrownBy(() -> service.close(initial.sessionId())).isInstanceOf(CodingSessionConflictException.class);
        verify(repository, never()).save(any());
        verifyNoInteractions(locks);
    }

    @Test void failedSessionCannotBypassProtection() {
        stored.set(initial.fail("PROCESS_CLEANUP_FAILED", "protected", Instant.now()));
        assertThatThrownBy(() -> service.close(initial.sessionId()))
                .extracting("code").isEqualTo("SESSION_NOT_CLOSABLE");
        verifyNoInteractions(locks, turns);
    }

    @Test void closeWaitsForPriorOperationAndRereadsLatestState() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var entered = new CountDownLatch(1);
            Future<CodingSession> result;
            synchronized (operations.lock(initial.sessionId())) {
                result = executor.submit(() -> { entered.countDown(); return service.close(initial.sessionId()); });
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                stored.set(initial.advanceReviewBaseline("next-module", Instant.now()));
                assertThat(result.isDone()).isFalse();
                verifyNoInteractions(turns, locks);
            }
            assertThat(result.get(5, TimeUnit.SECONDS).activeReviewBaselineCommit()).isEqualTo("next-module");
        }
    }

    @Test void rejectsEveryUnsupportedStatus() {
        for (var status : List.of(CodingSessionStatus.INITIALIZING, CodingSessionStatus.LEGACY_READ_ONLY,
                CodingSessionStatus.MERGED)) {
            stored.set(new CodingSession(initial.sessionId(), initial.title(), status,
                    initial.sourceRepository(), initial.worktreePath(), initial.baseRef(), initial.resolvedCommit(),
                    initial.activeReviewBaselineCommit(), true, initial.changePolicy(), null,
                    initial.createdAt(), initial.updatedAt(), null, null));
            assertThatThrownBy(() -> service.close(initial.sessionId()))
                    .extracting("code").isEqualTo("SESSION_NOT_CLOSABLE");
        }
        verifyNoInteractions(locks, turns);
    }

    @Test void concurrentCloseUsesOneTimestamp() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<CodingSession> close = () -> { start.await(); return service.close(initial.sessionId()); };
            var first = executor.submit(close);
            var second = executor.submit(close);
            start.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(second.get(5, TimeUnit.SECONDS));
            verify(repository, times(1)).save(any());
        }
    }
}
