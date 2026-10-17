package com.codingreview.controlplane.review;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReviewResultV2Test {
    private ObjectNode result() throws Exception {
        var value = (ObjectNode) ReviewJson.parse(Files.readAllBytes(
                Path.of("../../contracts/fixtures/review-real-v1/examples.json")))
                .path("review-harness-result-v2").path("positive").deepCopy();
        ((ObjectNode) value.path("passResults").get(0)).put("scopeKind", "FINAL_FILES");
        String markdown = "# Correctness review draft\n\n"
                + "Status: INCOMPLETE; scope: FINAL_FILES; coverage: PARTIAL.\n\n"
                + "- src/Demo.java\n\n## Limitations\n\n"
                + "- Call budget exhausted\n- MODEL\\_CALL\\_LIMIT\n"
                + "\nNo validated finding is delivered for this finite scope. This is not a whole-project correctness guarantee.\n";
        ((ObjectNode) value.path("reportDraft")).put("markdown", markdown)
                .put("byteSize", markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        return value;
    }

    @Test void importsFiniteIncompleteReportAndRejectsInventedMarkdown() throws Exception {
        var value = result();
        ReviewResultV2.validate(value);
        ((ObjectNode) value.path("reportDraft")).put("markdown", "invented finding").put("byteSize", 16);
        assertThatThrownBy(() -> ReviewResultV2.validate(value)).isInstanceOf(ReviewException.class);
    }

    @Test void rejectsDanglingPassReferencesAndFalseCompletion() throws Exception {
        var value = result();
        ((ObjectNode) value.path("passResults").get(0)).withArray("findingIds")
                .add("55555555-5555-4555-8555-555555555555");
        assertThatThrownBy(() -> ReviewResultV2.validate(value)).isInstanceOf(ReviewException.class);
        var complete = result();
        ((ObjectNode) complete.path("passResults").get(0)).put("status", "COMPLETED");
        assertThatThrownBy(() -> ReviewResultV2.validate(complete)).isInstanceOf(ReviewException.class);
    }

    @Test void importsTheSameOriginalResultIdempotentlyAndRejectsWrongNegotiatedVersion(
            @org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        var store = new ReviewExecutionStore(root);
        var run = ReviewExecution.preparing(java.util.UUID.randomUUID());
        run = run.submitting(new com.codingreview.controlplane.review.snapshot.SnapshotPublication(
                1, run.snapshotId(), run.sessionId(), "sha256:" + "a".repeat(64), run.createdAt()),
                "review", java.util.Map.of("executionMode", "REAL", "resultVersion", "2.0"));
        store.save(run);
        var value = result();
        value.put("runId", run.runId().toString()).put("snapshotId", run.snapshotId().toString())
                .put("generatedAt", run.createdAt());
        byte[] raw = ReviewJson.canonical(value);
        String hash = ReviewJson.sha256(raw);
        var descriptor = ReviewJson.parse(ReviewJson.canonical(java.util.Map.of("resultId", value.path("resultId").asText(),
                "resultVersion", "2.0", "resultSha256", hash)));
        var importer = new ReviewFactImporter(store, new ReviewExecutionGuard(store),
                new com.codingreview.controlplane.application.session.SessionOperationLockManager());
        String[] states = {"PENDING", "RUNNING", "SUCCEEDED"};
        String[] resources = {"NOT_ACQUIRED", "ACTIVE", "RELEASED"};
        String[] types = {"REVIEW_RUN_ACCEPTED", "REVIEW_RUN_STARTED", "REVIEW_RUN_SUCCEEDED"};
        for (int i = 0; i < 3; i++) {
            var payload = new java.util.LinkedHashMap<String, Object>();
            payload.put("status", states[i]); payload.put("resourceState", resources[i]);
            if (i == 2) payload.put("result", descriptor);
            importer.event(run.sessionId(), run.runId(), ReviewJson.canonical(java.util.Map.of(
                    "protocolVersion", "1.0", "runId", run.runId().toString(), "sequence", i + 1,
                    "eventType", types[i], "occurredAt", run.createdAt(), "payload", payload)));
        }
        var status = new java.util.LinkedHashMap<String, Object>();
        status.put("protocolVersion", "1.0"); status.put("runId", run.runId().toString());
        status.put("status", "SUCCEEDED"); status.put("resourceState", "RELEASED");
        for (var field : java.util.List.of("createdAt", "updatedAt", "startedAt", "finishedAt")) status.put(field, run.createdAt());
        status.put("lastEventSequence", 3); status.put("result", descriptor);
        status.put("failure", null); status.put("recoveryCode", null);
        byte[] statusRaw = ReviewJson.canonical(status);
        var response = new ReviewHarnessClient.Result(raw, hash, "\"" + hash + "\"");
        var completed = importer.finish(run.sessionId(), run.runId(), statusRaw, response);
        assertThat(completed.state()).isEqualTo("COMPLETED");
        assertThat(store.result(completed)).containsExactly(raw);
        assertThat(importer.finish(run.sessionId(), run.runId(), statusRaw, response)).isEqualTo(completed);
        value.put("resultVersion", "1.0");
        var originalRun = run;
        assertThatThrownBy(() -> ReviewProtocol.result(ReviewJson.canonical(value), hash, "\"" + hash + "\"",
                originalRun, ReviewJson.parse(statusRaw))).isInstanceOf(ReviewException.class);
    }
}
