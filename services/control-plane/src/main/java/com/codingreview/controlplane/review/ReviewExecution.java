package com.codingreview.controlplane.review;

import com.codingreview.controlplane.review.snapshot.SnapshotPublication;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.*;

/** Java 的导入进度，不是第二套 Python 执行状态机。 */
public record ReviewExecution(
        int schemaVersion,
        UUID runId,
        UUID sessionId,
        UUID reviewThreadId,
        UUID snapshotId,
        String manifestSha256,
        String requestBase64,
        String requestFingerprint,
        String state,
        boolean cancellationRequested,
        String pythonStatus,
        String resourceState,
        long eventCursor,
        long observedLastEventSequence,
        JsonNode result,
        String createdAt,
        String updatedAt,
        String diagnosticCode) {
    public static final Set<String> STATES =
            Set.of(
                    "PREPARING",
                    "SUBMITTING",
                    "SUBMISSION_UNKNOWN",
                    "ACTIVE",
                    "FINALIZING",
                    "COMPLETED",
                    "FAILED",
                    "CANCELLED",
                    "EXECUTION_BLOCKED");

    public ReviewExecution {
        // Jackson represents a JSON null as NullNode when reading a tree.
        result = result == null || result.isNull() ? null : result;
        Objects.requireNonNull(runId);
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(reviewThreadId);
        Objects.requireNonNull(snapshotId);
        if (schemaVersion != 1
                || !STATES.contains(state)
                || eventCursor < 0
                || observedLastEventSequence < eventCursor)
            throw new ReviewException("REVIEW_HISTORY_CORRUPTED");
        if (createdAt == null
                || updatedAt == null
                || !createdAt.matches(".*\\.\\d{6}Z")
                || !updatedAt.matches(".*\\.\\d{6}Z")
                || updatedAt.compareTo(createdAt) < 0)
            throw new ReviewException("REVIEW_HISTORY_CORRUPTED");
        try {
            Instant.parse(createdAt);
            Instant.parse(updatedAt);
        } catch (RuntimeException e) {
            throw new ReviewException("REVIEW_HISTORY_CORRUPTED");
        }
        if (!Set.of("NOT_ACQUIRED", "ACTIVE", "RELEASING", "RELEASED", "UNCERTAIN")
                .contains(resourceState)) throw new ReviewException("REVIEW_HISTORY_CORRUPTED");
        if (pythonStatus == null
                && (eventCursor != 0
                        || observedLastEventSequence != 0
                        || result != null
                        || !resourceState.equals("NOT_ACQUIRED")))
            throw new ReviewException("REVIEW_HISTORY_CORRUPTED");
        if (pythonStatus != null
                && (!Set.of("PENDING", "RUNNING", "CANCELLING", "SUCCEEDED", "FAILED", "CANCELLED")
                                .contains(pythonStatus)
                        || observedLastEventSequence < 1))
            throw new ReviewException("REVIEW_HISTORY_CORRUPTED");
        if ((state.equals("COMPLETED") && !"SUCCEEDED".equals(pythonStatus))
                || (state.equals("CANCELLED") && !"CANCELLED".equals(pythonStatus))
                || (state.equals("FAILED")
                        && requestBase64 != null
                        && !"FAILED".equals(pythonStatus)))
            throw new ReviewException("REVIEW_HISTORY_CORRUPTED");
        if ((requestBase64 == null) != (requestFingerprint == null)
                || (requestBase64 != null && manifestSha256 == null))
            throw new ReviewException("REVIEW_HISTORY_CORRUPTED");
        if (requestBase64 == null
                && (pythonStatus != null
                        || eventCursor != 0
                        || observedLastEventSequence != 0
                        || result != null
                        || !"NOT_ACQUIRED".equals(resourceState)))
            throw new ReviewException("REVIEW_HISTORY_CORRUPTED");
        if (manifestSha256 != null && !manifestSha256.matches("sha256:[0-9a-f]{64}"))
            throw new ReviewException("REVIEW_HISTORY_CORRUPTED");
        if (diagnosticCode != null && !diagnosticCode.matches("[A-Z][A-Z0-9_]{0,127}"))
            throw new ReviewException("REVIEW_HISTORY_CORRUPTED");
        if (requestBase64 != null) {
            byte[] raw;
            try {
                raw = Base64.getDecoder().decode(requestBase64);
            } catch (IllegalArgumentException e) {
                throw new ReviewException("REVIEW_HISTORY_CORRUPTED");
            }
            if (raw.length > 65536 || !ReviewJson.sha256(raw).equals(requestFingerprint))
                throw new ReviewException("REVIEW_HISTORY_CORRUPTED");
            JsonNode request = ReviewJson.parse(raw);
            if (!request.path("runId").asText().equals(runId.toString())
                    || !request.path("codingSessionId").asText().equals(sessionId.toString())
                    || !request.path("reviewThreadId").asText().equals(reviewThreadId.toString())
                    || !request.path("snapshot")
                            .path("snapshotId")
                            .asText()
                            .equals(snapshotId.toString())
                    || !request.path("snapshot")
                            .path("manifestSha256")
                            .asText()
                            .equals(manifestSha256))
                throw new ReviewException("REVIEW_HISTORY_CORRUPTED");
        } else if (!Set.of("PREPARING", "FAILED", "EXECUTION_BLOCKED").contains(state))
            throw new ReviewException("REVIEW_HISTORY_CORRUPTED");
        result = result == null ? null : result.deepCopy();
    }

    @Override
    public JsonNode result() {
        return result == null ? null : result.deepCopy();
    }

    public byte[] requestBytes() {
        return requestBase64 == null ? null : Base64.getDecoder().decode(requestBase64);
    }

    public static ReviewExecution preparing(UUID sessionId) {
        String at = ReviewJson.timestamp(Instant.now());
        return new ReviewExecution(
                1,
                UUID.randomUUID(),
                sessionId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null,
                null,
                "PREPARING",
                false,
                null,
                "NOT_ACQUIRED",
                0,
                0,
                null,
                at,
                at,
                null);
    }

    public ReviewExecution submitting(SnapshotPublication snapshot, String message) {
        return submitting(
                snapshot, message, Map.of("executionMode", "SIMULATED", "resultVersion", "1.0"));
    }

    public ReviewExecution submitting(SnapshotPublication snapshot, String message, Map<String, String> executionContract) {
        ReviewWireSchemas.validate(
                "review-execution-contract-v1",
                ReviewJson.parse(ReviewJson.canonical(executionContract)));
        byte[] request =
                ReviewJson.canonical(
                        Map.of(
                                "protocolVersion",
                                "1.0",
                                "runId",
                                runId.toString(),
                                "codingSessionId",
                                sessionId.toString(),
                                "reviewThreadId",
                                reviewThreadId.toString(),
                                "snapshot",
                                Map.of(
                                        "snapshotId",
                                        snapshotId.toString(),
                                        "manifestSha256",
                                        snapshot.manifestSha256()),
                                "input",
                                Map.of("message", message),
                                "executionContract", executionContract));
        return submittingRequest(snapshot, message, request);
    }

    public ReviewExecution submitting(
            SnapshotPublication snapshot, String message, JsonNode reviewPlan) {
        ReviewPlan.validate(reviewPlan);
        var request = new LinkedHashMap<String, Object>();
        request.put("protocolVersion", "1.1");
        request.put("runId", runId.toString());
        request.put("codingSessionId", sessionId.toString());
        request.put("reviewThreadId", reviewThreadId.toString());
        request.put(
                "snapshot",
                Map.of(
                        "snapshotId",
                        snapshotId.toString(),
                        "manifestSha256",
                        snapshot.manifestSha256()));
        request.put("input", Map.of("message", message));
        request.put("executionContract", Map.of("executionMode", "REAL", "resultVersion", "3.0"));
        request.put("reviewPlan", reviewPlan);
        byte[] raw = ReviewJson.canonical(request);
        ReviewWireSchemas.validate("review-harness-create-run-v1.1", ReviewJson.parse(raw));
        return submittingRequest(snapshot, message, raw);
    }

    private ReviewExecution submittingRequest(
            SnapshotPublication snapshot, String message, byte[] request) {
        if (request.length > 65536
                || message.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32768
                || message.isBlank()) throw new ReviewException("REVIEW_REQUEST_TOO_LARGE");
        return new ReviewExecution(
                1,
                runId,
                sessionId,
                reviewThreadId,
                snapshotId,
                snapshot.manifestSha256(),
                Base64.getEncoder().encodeToString(request),
                ReviewJson.sha256(request),
                "SUBMITTING",
                cancellationRequested,
                pythonStatus,
                resourceState,
                eventCursor,
                observedLastEventSequence,
                result,
                createdAt,
                now(),
                null);
    }

    public ReviewExecution state(String target, String diagnostic) {
        return new ReviewExecution(
                1,
                runId,
                sessionId,
                reviewThreadId,
                snapshotId,
                manifestSha256,
                requestBase64,
                requestFingerprint,
                target,
                cancellationRequested,
                pythonStatus,
                resourceState,
                eventCursor,
                observedLastEventSequence,
                result,
                createdAt,
                now(),
                diagnostic);
    }

    public ReviewExecution cancelIntent() {
        return new ReviewExecution(
                1,
                runId,
                sessionId,
                reviewThreadId,
                snapshotId,
                manifestSha256,
                requestBase64,
                requestFingerprint,
                state,
                true,
                pythonStatus,
                resourceState,
                eventCursor,
                observedLastEventSequence,
                result,
                createdAt,
                now(),
                diagnosticCode);
    }

    public ReviewExecution project(JsonNode facts, long cursor, long observed, String target) {
        JsonNode descriptor = facts.get("result");
        return new ReviewExecution(
                1,
                runId,
                sessionId,
                reviewThreadId,
                snapshotId,
                manifestSha256,
                requestBase64,
                requestFingerprint,
                target,
                cancellationRequested,
                facts.path("status").asText(),
                facts.path("resourceState").asText(),
                cursor,
                Math.max(observed, observedLastEventSequence),
                descriptor == null || descriptor.isNull() ? result : descriptor,
                createdAt,
                now(),
                null);
    }

    public boolean blocks() {
        if (state.equals("FAILED")
                && requestBase64 == null
                && "NOT_ACQUIRED".equals(resourceState)
                && diagnosticCode != null) return false;
        return !Set.of("COMPLETED", "FAILED", "CANCELLED").contains(state)
                || !Set.of("NOT_ACQUIRED", "RELEASED").contains(resourceState)
                || !Set.of("SUCCEEDED", "FAILED", "CANCELLED")
                        .contains(pythonStatus == null ? "" : pythonStatus)
                || eventCursor != observedLastEventSequence;
    }

    private String now() {
        String at = ReviewJson.timestamp(Instant.now());
        return at.compareTo(updatedAt) < 0 ? updatedAt : at;
    }

    public Map<String, Object> wire() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("runId", runId);
        value.put("sessionId", sessionId);
        value.put("reviewThreadId", reviewThreadId);
        value.put("snapshotId", snapshotId);
        value.put("status", state);
        value.put("pythonStatus", pythonStatus);
        value.put("resourceState", resourceState);
        value.put("cancellationRequested", cancellationRequested);
        value.put("eventCursor", eventCursor);
        value.put("lastEventSequence", observedLastEventSequence);
        value.put("result", result);
        value.put("diagnosticCode", diagnosticCode);
        value.put("createdAt", createdAt);
        value.put("updatedAt", updatedAt);
        value.put(
                "simulationOnly",
                requestBytes() == null
                        || !ReviewJson.parse(requestBytes())
                                .path("executionContract")
                                .path("executionMode")
                                .asText()
                                .equals("REAL"));
        return value;
    }
}
