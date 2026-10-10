package com.codingreview.controlplane.review;

import static org.assertj.core.api.Assertions.*;

import com.codingreview.controlplane.application.session.*;
import com.codingreview.controlplane.review.snapshot.*;
import com.codingreview.controlplane.session.model.CodingSession;
import com.codingreview.controlplane.session.persistence.FileCodingSessionRepository;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicy;
import com.codingreview.controlplane.workspace.git.model.GitRepositorySnapshot;
import com.codingreview.controlplane.workspace.lock.FileRepositoryWriteLockManager;
import com.codingreview.controlplane.workspace.lock.RepositoryWriteLockException;
import com.fasterxml.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

class ReviewRecoveryTest {
    @TempDir Path root;

    @Test
    void closedAndFailedSessionKeepRealRepositoryOwnershipForPossiblySentReview() throws Exception {
        var sessions =
                new FileCodingSessionRepository(
                        root, JsonMapper.builder().findAndAddModules().build());
        var store = new ReviewExecutionStore(root);
        var snapshots = Files.createDirectory(root.resolve("snapshots"));
        var executions = Files.createDirectory(root.resolve("executions"));
        var capture = new SnapshotCapture(snapshots, executions, SnapshotLimits.defaults());
        for (boolean closed : List.of(true, false)) {
            var source =
                    Files.createDirectory(
                            root.resolve(closed ? "closed-repository" : "failed-repository"));
            var session =
                    CodingSession.initializing(
                                    UUID.randomUUID(),
                                    "protected",
                                    source,
                                    "main",
                                    new ChangePolicy(List.of(), List.of()),
                                    Instant.now())
                            .activateRealRepository(
                                    new GitRepositorySnapshot(
                                            source, "main", "a".repeat(40), false),
                                    Instant.now());
            session =
                    closed
                            ? session.close(Instant.now())
                            : session.fail("UNRELATED_FAILURE", "failure", Instant.now());
            sessions.save(session);
            var run = ReviewExecution.preparing(session.sessionId());
            store.save(
                    run.submitting(
                            new SnapshotPublication(
                                    1,
                                    run.snapshotId(),
                                    run.sessionId(),
                                    "sha256:" + "a".repeat(64),
                                    run.createdAt()),
                            "simulation"));
            try (var repositories = new FileRepositoryWriteLockManager(root)) {
                repositories.acquire(source, session.sessionId());
            }
            for (int restart = 0; restart < 2; restart++) {
                var guard = new ReviewExecutionGuard(new ReviewExecutionStore(root));
                new ReviewRecoveryService(
                                store,
                                guard,
                                capture,
                                new SessionOperationLockManager(),
                                new SnapshotPublicationStore(root))
                        .run(null);
                try (var repositories = new FileRepositoryWriteLockManager(root)) {
                    new CodingSessionRecoveryService(sessions, repositories, guard).run(null);
                    assertThat(guard.blocks(session.sessionId())).isTrue();
                    assertThatThrownBy(() -> repositories.acquire(source, UUID.randomUUID()))
                            .isInstanceOf(RepositoryWriteLockException.class);
                }
            }
            assertThat(sessions.find(session.sessionId())).contains(session);
        }
    }

    @Test
    void malformedPublishedSnapshotCannotReleaseUnsentPreparationProtection() throws Exception {
        var store = new ReviewExecutionStore(root);
        var guard = new ReviewExecutionGuard(store);
        var snapshots = Files.createDirectory(root.resolve("snapshots"));
        var executions = Files.createDirectory(root.resolve("executions"));
        var run = ReviewExecution.preparing(UUID.randomUUID());
        store.save(run.state("EXECUTION_BLOCKED", "SNAPSHOT_STATE_WRITE_FAILED"));
        var published = Files.createDirectory(snapshots.resolve(run.snapshotId().toString()));
        Files.writeString(published.resolve("manifest.json"), "{}");
        new ReviewRecoveryService(
                        store,
                        guard,
                        new SnapshotCapture(snapshots, executions, SnapshotLimits.defaults()),
                        new SessionOperationLockManager(),
                        new SnapshotPublicationStore(root))
                .run(null);
        assertThat(guard.blocks(run.sessionId())).isTrue();
        assertThat(published.resolve("manifest.json")).hasContent("{}");
    }
}
