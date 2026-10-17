package com.codingreview.controlplane.review;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.codingreview.controlplane.application.session.*;
import com.codingreview.controlplane.observability.run.RunEventJournal;
import com.codingreview.controlplane.review.snapshot.*;
import com.codingreview.controlplane.runtime.readiness.RuntimeReadinessService;
import com.codingreview.controlplane.session.guard.SessionWorkspaceGuard;
import com.codingreview.controlplane.session.model.CodingSession;
import com.codingreview.controlplane.session.persistence.*;
import com.codingreview.controlplane.workflow.coding.CodingTurnOrchestrator;
import com.codingreview.controlplane.workspace.change.GitChangeCollector;
import com.codingreview.controlplane.workspace.change.policy.ChangePolicyEvaluatorImpl;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicy;
import com.codingreview.controlplane.workspace.git.GitRepositoryInspector;
import com.codingreview.controlplane.workspace.git.model.GitRepositorySnapshot;
import com.codingreview.controlplane.workspace.lock.FileRepositoryWriteLockManager;
import com.fasterxml.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;

class ReviewGateTest {
    @TempDir Path root;

    @Test
    void durablePreparationBlocksCodingBaselineReviewAndCloseBeforeAnyExternalCall()
            throws Exception {
        var mapper = JsonMapper.builder().findAndAddModules().build();
        var sessions = new FileCodingSessionRepository(root, mapper);
        var turns = new FileCodingTurnRepository(root, mapper);
        var source = Files.createDirectory(root.resolve("repository"));
        var session =
                CodingSession.initializing(
                                UUID.randomUUID(),
                                "gate",
                                source,
                                "main",
                                new ChangePolicy(List.of(), List.of()),
                                Instant.now())
                        .activateRealRepository(
                                new GitRepositorySnapshot(source, "main", "a".repeat(40), false),
                                Instant.now());
        sessions.save(session);
        var store = new ReviewExecutionStore(root);
        store.save(ReviewExecution.preparing(session.sessionId()));
        // Recreated guard proves protection comes from disk, without a handle.
        var guard = new ReviewExecutionGuard(new ReviewExecutionStore(root));
        var locks = new SessionOperationLockManager();
        var changeCollector = mock(GitChangeCollector.class);
        var inspector = mock(GitRepositoryInspector.class);
        var readiness = mock(RuntimeReadinessService.class);
        var coding =
                new CodingTurnApplicationService(
                        sessions,
                        turns,
                        mock(CodingTurnOrchestrator.class),
                        changeCollector,
                        mock(RunEventJournal.class),
                        mapper,
                        task -> {
                            throw new AssertionError("must not execute");
                        },
                        readiness,
                        new SessionWorkspaceGuard(inspector),
                        locks,
                        guard);
        var baseline =
                new ReviewBaselineApplicationService(
                        sessions, turns, inspector, changeCollector, locks, coding, guard);
        var snapshots = Files.createDirectory(root.resolve("snapshots"));
        var executions = Files.createDirectory(root.resolve("executions"));
        var publications = new SnapshotPublicationStore(root);
        try (var repositories = new FileRepositoryWriteLockManager(root);
                var client =
                        new ReviewHarnessClient(
                                URI.create("http://127.0.0.1:1"),
                                "test-only",
                                Duration.ofSeconds(1),
                                Duration.ofSeconds(1),
                                Duration.ofSeconds(1));
                var coordinator =
                        new ReviewCoordinator(
                                store,
                                guard,
                                client,
                                new ReviewFactImporter(store, guard, locks),
                                publications,
                                locks,
                                1,
                                Duration.ofSeconds(5))) {
            repositories.acquire(source, session.sessionId());
            var close =
                    new CodingSessionCloseApplicationService(
                            sessions, coding, locks, repositories, guard);
            var review =
                    new ReviewApplicationService(
                            sessions,
                            locks,
                            coding,
                            new SessionWorkspaceGuard(inspector),
                            new ChangePolicyEvaluatorImpl(),
                            new SnapshotCapture(snapshots, executions, SnapshotLimits.defaults()),
                            publications,
                            store,
                            guard,
                            client,
                            coordinator);
            assertThatThrownBy(() -> coding.submit(session.sessionId(), "later coding"))
                    .extracting("code")
                    .isEqualTo("REVIEW_EXECUTION_BLOCKED");
            assertThat(baseline.readiness(session.sessionId()).blockers())
                    .contains("REVIEW_EXECUTION_BLOCKED");
            assertThatThrownBy(
                            () ->
                                    baseline.advance(
                                            session.sessionId(),
                                            new AdvanceReviewBaselineRequest(
                                                    "a".repeat(40),
                                                    "a".repeat(40),
                                                    "sha256:" + "a".repeat(64))))
                    .extracting("code")
                    .isEqualTo("BASELINE_NOT_READY");
            assertThatThrownBy(() -> review.preview(session.sessionId()))
                    .extracting("code")
                    .isEqualTo("REVIEW_EXECUTION_BLOCKED");
            assertThatThrownBy(
                            () ->
                                    review.start(
                                            session.sessionId(),
                                            new ScopeConfirmation(
                                                    "a".repeat(40),
                                                    "a".repeat(40),
                                                    "main",
                                                    "sha256:" + "a".repeat(64),
                                                    List.of("x")),
                                            "later review"))
                    .extracting("code")
                    .isEqualTo("REVIEW_EXECUTION_BLOCKED");
            assertThatThrownBy(() -> close.close(session.sessionId()))
                    .extracting("code")
                    .isEqualTo("REVIEW_EXECUTION_BLOCKED");
            assertThat(sessions.find(session.sessionId())).contains(session);
            assertThat(turns.findAll(session.sessionId())).isEmpty();
            assertThat(store.list(session.sessionId(), 10)).hasSize(1);
            verifyNoInteractions(inspector, changeCollector, readiness);
        }
    }
}
