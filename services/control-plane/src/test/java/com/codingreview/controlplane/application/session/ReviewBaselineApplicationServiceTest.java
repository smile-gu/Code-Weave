package com.codingreview.controlplane.application.session;

import com.codingreview.controlplane.application.session.error.CodingSessionConflictException;
import com.codingreview.controlplane.session.model.CodingSession;
import com.codingreview.controlplane.session.model.CodingTurn;
import com.codingreview.controlplane.session.model.TurnOutcome;
import com.codingreview.controlplane.session.persistence.CodingSessionRepository;
import com.codingreview.controlplane.session.persistence.CodingTurnRepository;
import com.codingreview.controlplane.workspace.change.GitChangeCollector;
import com.codingreview.controlplane.workspace.change.WorkspaceFingerprintCalculator;
import com.codingreview.controlplane.workspace.change.model.GitChangeSet;
import com.codingreview.controlplane.workspace.change.model.GitChangeType;
import com.codingreview.controlplane.workspace.change.model.GitChangedFile;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicy;
import com.codingreview.controlplane.workspace.git.GitRepositoryInspector;
import com.codingreview.controlplane.workspace.git.model.GitRepositorySnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证模块正式提交推进活动审查基线的业务门禁。 */
class ReviewBaselineApplicationServiceTest {
    private static final String INITIAL_COMMIT = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String NEW_COMMIT = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @TempDir
    Path tempDir;

    @Test
    void advancesVerifiedCleanDescendantAndPreservesInitialBaseline() {
        Fixture fixture = fixture(false, true);
        ReviewBaselineReadiness readiness = fixture.service.readiness(
                fixture.session.sessionId()
        );

        CodingSession advanced = fixture.service.advance(
                fixture.session.sessionId(),
                new AdvanceReviewBaselineRequest(
                        readiness.activeBaselineCommit(), readiness.currentHead(),
                        readiness.workspaceFingerprint()
                )
        );

        assertThat(readiness.ready()).isTrue();
        assertThat(advanced.resolvedCommit()).isEqualTo(INITIAL_COMMIT);
        assertThat(advanced.activeReviewBaselineCommit()).isEqualTo(NEW_COMMIT);
        ArgumentCaptor<CodingSession> saved = ArgumentCaptor.forClass(CodingSession.class);
        verify(fixture.sessions).save(saved.capture());
        assertThat(saved.getValue()).isEqualTo(advanced);
    }

    @Test
    void refusesDirtyWorkspaceEvenWhenHeadAdvanced() {
        Fixture fixture = fixture(true, true);

        ReviewBaselineReadiness readiness = fixture.service.readiness(
                fixture.session.sessionId()
        );

        assertThat(readiness.ready()).isFalse();
        assertThat(readiness.blockers()).contains("UNCOMMITTED_CHANGES");
        assertThatThrownBy(() -> fixture.service.advance(
                fixture.session.sessionId(),
                new AdvanceReviewBaselineRequest(INITIAL_COMMIT, NEW_COMMIT, "fingerprint")
        )).isInstanceOf(CodingSessionConflictException.class)
                .hasMessageContaining("UNCOMMITTED_CHANGES");
        verify(fixture.sessions, never()).save(any());
    }

    @Test
    void refusesCommitThatDoesNotDescendFromActiveBaseline() {
        Fixture fixture = fixture(false, false);

        ReviewBaselineReadiness readiness = fixture.service.readiness(
                fixture.session.sessionId()
        );

        assertThat(readiness.ready()).isFalse();
        assertThat(readiness.blockers()).contains("HEAD_NOT_DESCENDANT");
    }

    @Test
    void refusesConfirmationWhenPreviewFingerprintIsStale() {
        Fixture fixture = fixture(false, true);
        ReviewBaselineReadiness readiness = fixture.service.readiness(
                fixture.session.sessionId()
        );

        assertThatThrownBy(() -> fixture.service.advance(
                fixture.session.sessionId(),
                new AdvanceReviewBaselineRequest(
                        readiness.activeBaselineCommit(), readiness.currentHead(),
                        "sha256:stale-preview"
                )
        )).isInstanceOf(CodingSessionConflictException.class)
                .satisfies(error -> assertThat(
                        ((CodingSessionConflictException) error).code()
                ).isEqualTo("BASELINE_PREVIEW_EXPIRED"));
        verify(fixture.sessions, never()).save(any());
    }

    @Test
    void closePreservesAdvancedBaselineAndBlocksLaterAdvance() {
        Fixture fixture = fixture(false, true);
        var stored = new java.util.concurrent.atomic.AtomicReference<>(fixture.session);
        when(fixture.sessions.find(fixture.session.sessionId())).thenAnswer(call -> Optional.of(stored.get()));
        org.mockito.Mockito.doAnswer(call -> { stored.set(call.getArgument(0)); return null; })
                .when(fixture.sessions).save(any());
        var readiness = fixture.service.readiness(fixture.session.sessionId());
        var request = new AdvanceReviewBaselineRequest(readiness.activeBaselineCommit(),
                readiness.currentHead(), readiness.workspaceFingerprint());
        fixture.service.advance(fixture.session.sessionId(), request);
        var close = new CodingSessionCloseApplicationService(fixture.sessions,
                mock(CodingTurnApplicationService.class), fixture.locks,
                mock(com.codingreview.controlplane.workspace.lock.RepositoryWriteLockManager.class));
        assertThat(close.close(fixture.session.sessionId()).activeReviewBaselineCommit()).isEqualTo(NEW_COMMIT);
        assertThatThrownBy(() -> fixture.service.advance(fixture.session.sessionId(), request))
                .hasMessageContaining("SESSION_NOT_ACTIVE");
    }

    /** 创建包含已验证 Turn 和可控 Git 事实的测试组件。 */
    private Fixture fixture(boolean dirty, boolean descendant) {
        CodingSessionRepository sessions = mock(CodingSessionRepository.class);
        CodingTurnRepository turns = mock(CodingTurnRepository.class);
        GitRepositoryInspector inspector = mock(GitRepositoryInspector.class);
        GitChangeCollector changes = mock(GitChangeCollector.class);
        Path repository = tempDir.resolve("review-baseline-repository");
        try {
            Files.createDirectories(repository.resolve("src/main/java/demo"));
            Files.writeString(repository.resolve("src/main/java/demo/App.java"),
                    "class App {}\n", StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建测试工作区。", exception);
        }
        UUID sessionId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-28T08:00:00Z");
        CodingSession session = CodingSession.initializing(
                sessionId, "活动基线测试", repository, "main",
                new ChangePolicy(List.of("src/**"), List.of(".git/**")), createdAt
        ).activateRealRepository(
                new GitRepositorySnapshot(repository, "main", INITIAL_COMMIT, false),
                createdAt.plusSeconds(1)
        );
        GitChangeSet changeSet = new GitChangeSet(
                INITIAL_COMMIT, repository,
                List.of(new GitChangedFile(
                        "src/main/java/demo/App.java", null, GitChangeType.MODIFIED, false
                )),
                "diff --git a/src/main/java/demo/App.java b/src/main/java/demo/App.java",
                68
        );
        String fingerprint = new WorkspaceFingerprintCalculator().calculate(changeSet);
        CodingTurn verified = CodingTurn.queued(
                UUID.randomUUID(), sessionId, 1, "修改代码", createdAt.plusSeconds(2)
        ).running(createdAt.plusSeconds(3)).complete(
                TurnOutcome.CHANGES_VERIFIED, 1, fingerprint,
                "Maven 验证通过", "已完成", "claude-session",
                createdAt.plusSeconds(4)
        );
        when(sessions.find(sessionId)).thenReturn(Optional.of(session));
        when(turns.findAll(sessionId)).thenReturn(List.of(verified));
        when(inspector.inspect(repository)).thenReturn(
                new GitRepositorySnapshot(repository, "main", NEW_COMMIT, dirty)
        );
        when(inspector.isAncestor(repository, INITIAL_COMMIT, NEW_COMMIT))
                .thenReturn(descendant);
        when(changes.collect(any())).thenReturn(changeSet);
        SessionOperationLockManager locks = new SessionOperationLockManager();
        ReviewBaselineApplicationService service = new ReviewBaselineApplicationService(
                sessions, turns, inspector, changes, locks
        );
        return new Fixture(session, sessions, service, locks);
    }

    /** 聚合基线测试所需的 Session、仓储和应用服务。 */
    private record Fixture(
            CodingSession session,
            CodingSessionRepository sessions,
            ReviewBaselineApplicationService service,
            SessionOperationLockManager locks
    ) {
    }
}
