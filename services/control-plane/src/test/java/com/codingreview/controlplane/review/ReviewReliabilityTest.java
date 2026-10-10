package com.codingreview.controlplane.review;

import static org.assertj.core.api.Assertions.*;

import com.codingreview.controlplane.application.session.SessionOperationLockManager;
import com.codingreview.controlplane.review.snapshot.SnapshotPublication;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

class ReviewReliabilityTest {
    private static final String AT = "2026-10-09T08:00:00.000000Z";
    @TempDir Path root;

    @Test
    void contradictoryLocalOnlyFailureCannotBypassPersistedHistoryGuard() throws Exception {
        var store = new ReviewExecutionStore(root);
        var run = pending();
        store.save(run);
        Path record =
                root.resolve("review-executions")
                        .resolve(run.sessionId().toString())
                        .resolve(run.runId().toString())
                        .resolve("execution.json");
        ObjectNode damaged = (ObjectNode) ReviewJson.parse(Files.readAllBytes(record));
        damaged.putNull("requestBase64");
        damaged.putNull("requestFingerprint");
        damaged.put("state", "FAILED");
        damaged.put("pythonStatus", "SUCCEEDED");
        damaged.put("resourceState", "RELEASED");
        damaged.put("eventCursor", 1);
        damaged.put("observedLastEventSequence", 1);
        Files.write(record, ReviewJson.canonical(damaged));
        assertThat(new ReviewExecutionGuard(store).blocks(run.sessionId())).isTrue();
    }

    @Test
    void replaysDurableEventAfterCursorWriteFailureAndKeepsLaterCancelIntent() {
        AtomicBoolean failCursor = new AtomicBoolean();
        var store =
                new ReviewExecutionStore(
                        root,
                        (source, target) -> {
                            var next = ReviewJson.parse(Files.readAllBytes(source));
                            if (target.getFileName().toString().equals("execution.json")
                                    && next.path("eventCursor").asLong() == 1
                                    && failCursor.get())
                                throw new IOException("before cursor commit");
                            Files.move(
                                    source,
                                    target,
                                    StandardCopyOption.ATOMIC_MOVE,
                                    StandardCopyOption.REPLACE_EXISTING);
                        });
        var run = pending();
        store.save(run);
        var guard = new ReviewExecutionGuard(store);
        var importer = new ReviewFactImporter(store, guard, new SessionOperationLockManager());
        byte[] accepted =
                event(
                        run,
                        1,
                        "REVIEW_RUN_ACCEPTED",
                        Map.of("status", "PENDING", "resourceState", "NOT_ACQUIRED"));
        failCursor.set(true);
        assertThatThrownBy(() -> importer.event(run.sessionId(), run.runId(), accepted))
                .isInstanceOf(ReviewException.class);
        assertThat(store.event(run, 1)).containsExactly(accepted);
        assertThat(store.find(run.sessionId(), run.runId()).orElseThrow().eventCursor()).isZero();
        assertThat(guard.blocks(run.sessionId())).isTrue();
        failCursor.set(false);
        store.save(store.find(run.sessionId(), run.runId()).orElseThrow().cancelIntent());
        importer.event(run.sessionId(), run.runId(), accepted);
        byte[] oldStatus = status(run, "PENDING", "NOT_ACQUIRED", 1, null);
        importer.event(
                run.sessionId(),
                run.runId(),
                event(
                        run,
                        2,
                        "REVIEW_RUN_STARTED",
                        Map.of("status", "RUNNING", "resourceState", "ACTIVE")));
        var current = importer.status(run.sessionId(), run.runId(), oldStatus);
        assertThat(current.eventCursor()).isEqualTo(2);
        assertThat(current.pythonStatus()).isEqualTo("RUNNING");
        assertThat(current.cancellationRequested()).isTrue();
    }

    @Test
    void tamperedResultAndHeadersCannotReleaseProtection() {
        var store = new ReviewExecutionStore(root);
        var guard = new ReviewExecutionGuard(store);
        var importer = new ReviewFactImporter(store, guard, new SessionOperationLockManager());
        var run = pending();
        store.save(run);
        UUID resultId = UUID.randomUUID();
        ObjectNode envelope =
                (ObjectNode)
                        ReviewJson.parse(
                                ReviewJson.canonical(
                                        Map.of(
                                                "resultVersion",
                                                "1.0",
                                                "resultId",
                                                resultId.toString(),
                                                "runId",
                                                run.runId().toString(),
                                                "snapshotId",
                                                run.snapshotId().toString(),
                                                "manifestSha256",
                                                run.manifestSha256(),
                                                "generatedAt",
                                                AT,
                                                "passResults",
                                                java.util.List.of(),
                                                "evidence",
                                                java.util.List.of(),
                                                "findings",
                                                java.util.List.of(),
                                                "reportDraft",
                                                Map.of("markdown", "", "byteSize", 0))));
        envelope.set(
                "diagnostics",
                ReviewJson.parse(
                        ReviewJson.canonical(
                                Map.of("contextManifestArtifactIds", java.util.List.of()))));
        byte[] result = ReviewJson.canonical(envelope);
        var descriptor =
                Map.of(
                        "resultVersion",
                        "1.0",
                        "resultId",
                        resultId.toString(),
                        "resultSha256",
                        ReviewJson.sha256(result));
        importer.event(
                run.sessionId(),
                run.runId(),
                event(
                        run,
                        1,
                        "REVIEW_RUN_ACCEPTED",
                        Map.of("status", "PENDING", "resourceState", "NOT_ACQUIRED")));
        importer.event(
                run.sessionId(),
                run.runId(),
                event(
                        run,
                        2,
                        "REVIEW_RUN_STARTED",
                        Map.of("status", "RUNNING", "resourceState", "ACTIVE")));
        importer.event(
                run.sessionId(),
                run.runId(),
                event(
                        run,
                        3,
                        "REVIEW_RUN_SUCCEEDED",
                        Map.of(
                                "status",
                                "SUCCEEDED",
                                "resourceState",
                                "RELEASED",
                                "result",
                                descriptor)));
        byte[] status = status(run, "SUCCEEDED", "RELEASED", 3, descriptor);
        importer.status(run.sessionId(), run.runId(), status);
        var tampered = result.clone();
        tampered[0] = '[';
        assertThatThrownBy(
                        () ->
                                importer.finish(
                                        run.sessionId(),
                                        run.runId(),
                                        status,
                                        new ReviewHarnessClient.Result(
                                                tampered,
                                                ReviewJson.sha256(result),
                                                "\"" + ReviewJson.sha256(result) + "\"")))
                .isInstanceOf(ReviewException.class);
        assertThatThrownBy(
                        () ->
                                importer.finish(
                                        run.sessionId(),
                                        run.runId(),
                                        status,
                                        new ReviewHarnessClient.Result(
                                                result,
                                                ReviewJson.sha256(result),
                                                "W/\"" + ReviewJson.sha256(result) + "\"")))
                .isInstanceOf(ReviewException.class);
        assertThat(guard.blocks(run.sessionId())).isTrue();
        assertThat(
                        importer.finish(
                                        run.sessionId(),
                                        run.runId(),
                                        status,
                                        new ReviewHarnessClient.Result(
                                                result,
                                                ReviewJson.sha256(result),
                                                "\"" + ReviewJson.sha256(result) + "\""))
                                .state())
                .isEqualTo("COMPLETED");
        assertThat(guard.blocks(run.sessionId())).isFalse();
    }

    private ReviewExecution pending() {
        var run = ReviewExecution.preparing(UUID.randomUUID());
        return run.submitting(
                new SnapshotPublication(
                        1,
                        run.snapshotId(),
                        run.sessionId(),
                        "sha256:" + "a".repeat(64),
                        run.createdAt()),
                "simulation");
    }

    private byte[] event(ReviewExecution run, int sequence, String type, Map<String, ?> payload) {
        return ReviewJson.canonical(
                Map.of(
                        "protocolVersion",
                        "1.0",
                        "runId",
                        run.runId().toString(),
                        "sequence",
                        sequence,
                        "eventType",
                        type,
                        "occurredAt",
                        AT,
                        "payload",
                        payload));
    }

    private byte[] status(
            ReviewExecution run,
            String state,
            String resource,
            int sequence,
            Map<String, ?> result) {
        ObjectNode value =
                (ObjectNode)
                        ReviewJson.parse(
                                ReviewJson.canonical(
                                        Map.of(
                                                "protocolVersion",
                                                "1.0",
                                                "runId",
                                                run.runId().toString(),
                                                "status",
                                                state,
                                                "resourceState",
                                                resource,
                                                "lastEventSequence",
                                                sequence,
                                                "createdAt",
                                                AT,
                                                "updatedAt",
                                                AT)));
        if (state.equals("PENDING")) value.putNull("startedAt");
        else value.put("startedAt", AT);
        if (state.equals("SUCCEEDED")) value.put("finishedAt", AT);
        else value.putNull("finishedAt");
        if (result == null) value.putNull("result");
        else value.set("result", ReviewJson.parse(ReviewJson.canonical(result)));
        value.putNull("failure");
        value.putNull("recoveryCode");
        return ReviewJson.canonical(value);
    }
}
