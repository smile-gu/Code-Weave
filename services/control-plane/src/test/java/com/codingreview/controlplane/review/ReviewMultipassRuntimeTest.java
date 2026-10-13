package com.codingreview.controlplane.review;

import static org.assertj.core.api.Assertions.*;

import com.codingreview.controlplane.review.snapshot.SnapshotPublication;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

class ReviewMultipassRuntimeTest {
    @Test
    void importsOrderedPassFactsAndOriginalResultIdempotently(
            @org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        var fixture =
                ReviewJson.parse(
                        Files.readAllBytes(
                                Path.of(
                                        "../../contracts/fixtures/review-multipass-v1/examples.json")));
        var value =
                (ObjectNode) fixture.path("review-harness-result-v3").path("positive").deepCopy();
        var plan = value.path("reviewPlan").deepCopy();
        var run = ReviewExecution.preparing(UUID.randomUUID());
        ((ObjectNode) plan.path("feeAuthorization")).put("confirmedAt", run.createdAt());
        run =
                run.submitting(
                        new SnapshotPublication(
                                1,
                                run.snapshotId(),
                                run.sessionId(),
                                "sha256:" + "a".repeat(64),
                                run.createdAt()),
                        "review",
                        plan);
        var store = new ReviewExecutionStore(root);
        store.save(run);
        value.put("runId", run.runId().toString())
                .put("snapshotId", run.snapshotId().toString())
                .put("generatedAt", run.createdAt());
        value.set("reviewPlan", plan);
        for (var evidence : value.path("evidence"))
            ((ObjectNode) evidence)
                    .put("runId", run.runId().toString())
                    .put("snapshotId", run.snapshotId().toString())
                    .put("capturedAt", run.createdAt());
        byte[] raw = ReviewJson.canonical(value);
        String hash = ReviewJson.sha256(raw);
        var descriptor =
                Map.of(
                        "resultId",
                        value.path("resultId").asText(),
                        "resultVersion",
                        "3.0",
                        "resultSha256",
                        hash);
        var importer =
                new ReviewFactImporter(
                        store,
                        new ReviewExecutionGuard(store),
                        new com.codingreview.controlplane.application.session
                                .SessionOperationLockManager());
        var progress = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 6; i++) {
            var pass = value.path("passResults").get(i);
            var fact = new LinkedHashMap<String, Object>();
            fact.put("runId", run.runId().toString());
            fact.put("passId", pass.path("passId").asText());
            fact.put("ordinal", i);
            fact.put("selected", pass.path("selected").asBoolean());
            fact.put("status", i == 0 ? "READY" : "SKIPPED");
            fact.put("revision", 1);
            fact.put("startedAt", null);
            fact.put("finishedAt", null);
            fact.put("reason", i == 0 ? null : "USER_NOT_SELECTED");
            fact.put("stateRevision", null);
            fact.put("stateDigest", null);
            fact.put(
                    "usage",
                    Map.of(
                            "modelCalls",
                            0,
                            "inputTokens",
                            0,
                            "outputTokens",
                            0,
                            "unknownCalls",
                            0));
            progress.add(fact);
        }
        var events = new ArrayList<byte[]>();
        String[] types = {
            "REVIEW_RUN_ACCEPTED",
            "REVIEW_RUN_STARTED",
            "REVIEW_PASS_UPDATED",
            "REVIEW_PASS_UPDATED",
            "REVIEW_PASS_UPDATED",
            "REVIEW_RUN_SUCCEEDED"
        };
        for (int i = 0; i < 6; i++) {
            if (i == 3) {
                progress.get(0).put("status", "RUNNING");
                progress.get(0).put("revision", 2);
                progress.get(0).put("startedAt", run.createdAt());
                progress.get(0).put("stateRevision", 1);
                progress.get(0).put("stateDigest", "sha256:" + "b".repeat(64));
            }
            if (i == 4) {
                var pass = value.path("passResults").get(0);
                var usage = (ObjectNode) pass.path("usage").deepCopy();
                usage.put("unknownCalls", 0);
                progress.get(0).put("status", pass.path("status").asText());
                progress.get(0).put("revision", 3);
                progress.get(0).put("finishedAt", run.createdAt());
                progress.get(0).put("reason", pass.path("incompleteReasons").get(0).asText());
                progress.get(0).put("stateRevision", 2);
                progress.get(0).put("usage", usage);
            }
            var payload = new LinkedHashMap<String, Object>();
            payload.put("status", i == 0 ? "PENDING" : i == 5 ? "SUCCEEDED" : "RUNNING");
            payload.put("resourceState", i == 0 ? "NOT_ACQUIRED" : i == 5 ? "RELEASED" : "ACTIVE");
            if (i >= 2 && i <= 4) payload.put("passes", progress);
            if (i == 5) payload.put("result", descriptor);
            byte[] event =
                    ReviewJson.canonical(
                            Map.of(
                                    "protocolVersion",
                                    "1.1",
                                    "runId",
                                    run.runId().toString(),
                                    "sequence",
                                    i + 1,
                                    "eventType",
                                    types[i],
                                    "occurredAt",
                                    run.createdAt(),
                                    "payload",
                                    payload));
            events.add(event);
            importer.event(run.sessionId(), run.runId(), event);
            importer.event(run.sessionId(), run.runId(), event);
        }
        var status = new LinkedHashMap<String, Object>();
        status.put("protocolVersion", "1.1");
        status.put("runId", run.runId().toString());
        status.put("status", "SUCCEEDED");
        status.put("resourceState", "RELEASED");
        for (String name : List.of("createdAt", "startedAt", "finishedAt", "updatedAt"))
            status.put(name, run.createdAt());
        status.put("lastEventSequence", 6);
        status.put("result", descriptor);
        status.put("failure", null);
        status.put("recoveryCode", null);
        var response = new ReviewHarnessClient.Result(raw, hash, "\"" + hash + "\"");
        var completed =
                importer.finish(
                        run.sessionId(), run.runId(), ReviewJson.canonical(status), response);
        assertThat(completed.state()).isEqualTo("COMPLETED");
        assertThat(store.result(completed)).containsExactly(raw);
        assertThat(
                        importer.finish(
                                run.sessionId(),
                                run.runId(),
                                ReviewJson.canonical(status),
                                response))
                .isEqualTo(completed);
        ReviewProtocol.persisted(store, completed);
    }

    @Test
    void strictlyBindsResult3ToTheOriginalPlanAndProtocol() throws Exception {
        var fixture =
                ReviewJson.parse(
                        Files.readAllBytes(
                                Path.of(
                                        "../../contracts/fixtures/review-multipass-v1/examples.json")));
        var value =
                (ObjectNode) fixture.path("review-harness-result-v3").path("positive").deepCopy();
        var plan = value.path("reviewPlan").deepCopy();
        var run = ReviewExecution.preparing(UUID.randomUUID());
        ((ObjectNode) plan.path("feeAuthorization")).put("confirmedAt", run.createdAt());
        run =
                run.submitting(
                        new SnapshotPublication(
                                1,
                                run.snapshotId(),
                                run.sessionId(),
                                "sha256:" + "a".repeat(64),
                                run.createdAt()),
                        "review",
                        plan);
        value.put("runId", run.runId().toString())
                .put("snapshotId", run.snapshotId().toString())
                .put("generatedAt", run.createdAt());
        value.set("reviewPlan", plan);
        for (var evidence : value.path("evidence"))
            ((ObjectNode) evidence)
                    .put("runId", run.runId().toString())
                    .put("snapshotId", run.snapshotId().toString())
                    .put("capturedAt", run.createdAt());
        byte[] raw = ReviewJson.canonical(value);
        String hash = ReviewJson.sha256(raw);
        var descriptor =
                Map.of(
                        "resultId",
                        value.path("resultId").asText(),
                        "resultVersion",
                        "3.0",
                        "resultSha256",
                        hash);
        var status = new LinkedHashMap<String, Object>();
        status.put("protocolVersion", "1.1");
        status.put("runId", run.runId().toString());
        status.put("status", "SUCCEEDED");
        status.put("resourceState", "RELEASED");
        for (String name : List.of("createdAt", "startedAt", "finishedAt", "updatedAt"))
            status.put(name, run.createdAt());
        status.put("lastEventSequence", 3);
        status.put("result", descriptor);
        status.put("failure", null);
        status.put("recoveryCode", null);
        byte[] statusRaw = ReviewJson.canonical(status);
        var parsed = ReviewProtocol.status(statusRaw, run.runId(), "1.1");
        ReviewProtocol.result(raw, hash, "\"" + hash + "\"", run, parsed);
        var original = run;
        assertThatThrownBy(() -> ReviewProtocol.status(statusRaw, original.runId()))
                .isInstanceOf(ReviewException.class);
        ((ObjectNode) value.path("reviewPlan").path("feeAuthorization"))
                .put("authorizationId", UUID.randomUUID().toString());
        byte[] tampered = ReviewJson.canonical(value);
        String changed = ReviewJson.sha256(tampered);
        ((ObjectNode) parsed.path("result")).put("resultSha256", changed);
        assertThatThrownBy(
                        () ->
                                ReviewProtocol.result(
                                        tampered, changed, "\"" + changed + "\"", original, parsed))
                .isInstanceOf(ReviewException.class);
    }
}
