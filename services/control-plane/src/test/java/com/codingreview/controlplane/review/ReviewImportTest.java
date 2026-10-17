package com.codingreview.controlplane.review;

import static org.assertj.core.api.Assertions.*;

import com.codingreview.controlplane.application.session.SessionOperationLockManager;
import com.codingreview.controlplane.review.snapshot.SnapshotPublication;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

class ReviewImportTest {
    @TempDir Path root;

    @Test
    void retainsProtectionWhenTerminalEventsOrOriginalResultAreMissing() {
        var store = new ReviewExecutionStore(root);
        var guard = new ReviewExecutionGuard(store);
        var run = ReviewExecution.preparing(UUID.randomUUID());
        run =
                run.submitting(
                        new SnapshotPublication(
                                1,
                                run.snapshotId(),
                                run.sessionId(),
                                "sha256:" + "a".repeat(64),
                                run.createdAt()),
                        "review");
        var status =
                ReviewJson.parse(
                        ("{\"protocolVersion\":\"1.0\",\"runId\":\""
                                        + run.runId()
                                        + "\",\"status\":\"FAILED\",\"resourceState\":\"NOT_ACQUIRED\",\"lastEventSequence\":2}")
                                .getBytes());
        run = run.project(status, 2, 2, "FAILED");
        store.save(run);
        assertThat(guard.blocks(run.sessionId())).isTrue();
    }

    @Test
    void storesEventBeforeCursorAndRejectsConflictAndGap() {
        var store = new ReviewExecutionStore(root);
        var run = ReviewExecution.preparing(UUID.randomUUID());
        run =
                run.submitting(
                        new SnapshotPublication(
                                1,
                                run.snapshotId(),
                                run.sessionId(),
                                "sha256:" + "a".repeat(64),
                                run.createdAt()),
                        "review");
        store.save(run);
        var importer =
                new ReviewFactImporter(
                        store, new ReviewExecutionGuard(store), new SessionOperationLockManager());
        var event =
                ReviewJson.parse(
                        ("{\"protocolVersion\":\"1.0\",\"runId\":\""
                                        + run.runId()
                                        + "\",\"sequence\":1,\"eventType\":\"REVIEW_RUN_ACCEPTED\",\"occurredAt\":\"2026-10-09T08:00:00.000000Z\",\"payload\":{\"status\":\"PENDING\",\"resourceState\":\"NOT_ACQUIRED\"}}")
                                .getBytes());
        byte[] raw = ReviewJson.canonical(event);
        importer.event(run.sessionId(), run.runId(), raw);
        assertThat(store.find(run.sessionId(), run.runId()).orElseThrow().eventCursor())
                .isEqualTo(1);
        assertThat(store.event(run, 1)).containsExactly(raw);
        importer.event(run.sessionId(), run.runId(), raw);
        var conflicting = event.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) conflicting)
                .put("occurredAt", "2026-10-09T08:00:01.000000Z");
        UUID session = run.sessionId(), id = run.runId();
        assertThatThrownBy(() -> importer.event(session, id, ReviewJson.canonical(conflicting)))
                .isInstanceOf(ReviewException.class);
        ((com.fasterxml.jackson.databind.node.ObjectNode) event).put("sequence", 3);
        assertThatThrownBy(() -> importer.event(session, id, ReviewJson.canonical(event)))
                .isInstanceOf(ReviewException.class);
        assertThat(store.find(session, id).orElseThrow().eventCursor()).isEqualTo(1);
    }
}
