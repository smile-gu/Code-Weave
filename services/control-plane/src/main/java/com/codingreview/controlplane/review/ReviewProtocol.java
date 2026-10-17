package com.codingreview.controlplane.review;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.*;

/** 冻结协议的有界校验，不把 Provider JSON 当成可直接应用的事实。 */
public final class ReviewProtocol {
    public static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELLED");
    private static final Map<String, Set<String>> RESOURCES =
            Map.of(
                    "PENDING",
                    Set.of("NOT_ACQUIRED"),
                    "RUNNING",
                    Set.of("ACTIVE"),
                    "CANCELLING",
                    Set.of("RELEASING"),
                    "SUCCEEDED",
                    Set.of("RELEASED"),
                    "FAILED",
                    Set.of("NOT_ACQUIRED", "RELEASED", "UNCERTAIN"),
                    "CANCELLED",
                    Set.of("NOT_ACQUIRED", "RELEASED"));

    private ReviewProtocol() {}

    private static JsonNode parse(byte[] raw, String code) {
        try {
            return ReviewJson.parse(raw);
        } catch (IllegalArgumentException error) {
            throw new ReviewException(code);
        }
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new ReviewException(code);
    }

    public static void fields(
            JsonNode value, Set<String> required, Set<String> optional, String code) {
        require(value != null && value.isObject(), code);
        Set<String> keys = new HashSet<>();
        value.fieldNames().forEachRemaining(keys::add);
        Set<String> allowed = new HashSet<>(required);
        allowed.addAll(optional);
        require(keys.containsAll(required) && allowed.containsAll(keys), code);
    }

    private static boolean uuid(JsonNode value) {
        return value != null
                && value.isTextual()
                && value.asText()
                        .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    private static boolean hash(JsonNode value) {
        return value != null && value.isTextual() && value.asText().matches("sha256:[0-9a-f]{64}");
    }

    private static void timestamp(JsonNode value, String code) {
        require(
                value != null
                        && value.isTextual()
                        && value.asText()
                                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z"),
                code);
        try {
            Instant.parse(value.asText());
        } catch (RuntimeException e) {
            throw new ReviewException(code);
        }
    }

    private static void recovery(JsonNode value, String code) {
        require(value.isTextual() && value.asText().matches("[A-Z][A-Z0-9_]{0,127}"), code);
    }

    private static void descriptor(JsonNode value, String code, String protocol) {
        fields(value, Set.of("resultId", "resultVersion", "resultSha256"), Set.of(), code);
        require(
                uuid(value.get("resultId"))
                        && (protocol.equals("1.1") ? Set.of("3.0") : Set.of("1.0", "2.0"))
                                .contains(value.path("resultVersion").asText())
                        && hash(value.get("resultSha256")),
                code);
    }

    private static void failure(JsonNode value, String code) {
        fields(value, Set.of("code", "message", "diagnosticId"), Set.of(), code);
        recovery(value.get("code"), code);
        require(uuid(value.get("diagnosticId")) && value.path("message").isTextual(), code);
        String message = value.path("message").asText();
        require(!message.isEmpty() && message.codePointCount(0, message.length()) <= 1024, code);
    }

    private static void combination(JsonNode value, String code) {
        String status = value.path("status").asText(),
                resource = value.path("resourceState").asText();
        require(RESOURCES.containsKey(status) && RESOURCES.get(status).contains(resource), code);
    }

    public static String protocolVersion(ReviewExecution run) {
        String value =
                run.requestBytes() == null
                        ? "1.0"
                        : ReviewJson.parse(run.requestBytes()).path("protocolVersion").asText();
        if (!Set.of("1.0", "1.1").contains(value))
            throw new ReviewException("REVIEW_HISTORY_CORRUPTED");
        return value;
    }

    public static JsonNode status(byte[] raw, UUID runId) {
        return status(raw, runId, "1.0");
    }

    public static JsonNode status(byte[] raw, UUID runId, String protocol) {
        String code = "REVIEW_EVENT_INTEGRITY_ERROR";
        if (raw.length > 65536) throw new ReviewException(code);
        JsonNode value = parse(raw, code);
        require(Set.of("1.0", "1.1").contains(protocol), code);
        fields(
                value,
                Set.of(
                        "protocolVersion",
                        "runId",
                        "status",
                        "resourceState",
                        "createdAt",
                        "startedAt",
                        "finishedAt",
                        "updatedAt",
                        "lastEventSequence",
                        "result",
                        "failure",
                        "recoveryCode"),
                Set.of(),
                code);
        require(
                value.path("protocolVersion").asText().equals(protocol)
                        && value.path("runId").asText().equals(runId.toString()),
                code);
        combination(value, code);
        JsonNode sequence = value.get("lastEventSequence");
        require(
                sequence.isIntegralNumber()
                        && sequence.canConvertToLong()
                        && sequence.asLong() >= 1,
                code);
        timestamp(value.get("createdAt"), code);
        timestamp(value.get("updatedAt"), code);
        require(
                value.path("createdAt").asText().compareTo(value.path("updatedAt").asText()) <= 0,
                code);
        String status = value.path("status").asText();
        require(value.get("finishedAt").isNull() != TERMINAL.contains(status), code);
        require(
                value.get("startedAt").isNull()
                        == value.path("resourceState").asText().equals("NOT_ACQUIRED"),
                code);
        for (String field : List.of("startedAt", "finishedAt"))
            if (!value.get(field).isNull()) {
                timestamp(value.get(field), code);
                require(
                        value.path(field).asText().compareTo(value.path("createdAt").asText()) >= 0
                                && value.path(field)
                                                .asText()
                                                .compareTo(value.path("updatedAt").asText())
                                        <= 0,
                        code);
            }
        if (!value.get("startedAt").isNull() && !value.get("finishedAt").isNull())
            require(
                    value.path("startedAt").asText().compareTo(value.path("finishedAt").asText())
                            <= 0,
                    code);
        if (status.equals("SUCCEEDED")) descriptor(value.get("result"), code, protocol);
        else require(value.get("result").isNull(), code);
        if (status.equals("FAILED")) failure(value.get("failure"), code);
        else require(value.get("failure").isNull(), code);
        if (!value.get("recoveryCode").isNull()) recovery(value.get("recoveryCode"), code);
        return value;
    }

    public static JsonNode event(byte[] raw, UUID runId) {
        return event(raw, runId, "1.0");
    }

    public static JsonNode event(byte[] raw, UUID runId, String protocol) {
        String code = "REVIEW_EVENT_INTEGRITY_ERROR";
        if (raw.length > 65536) throw new ReviewException(code);
        JsonNode value = parse(raw, code);
        require(Set.of("1.0", "1.1").contains(protocol), code);
        fields(
                value,
                Set.of(
                        "protocolVersion",
                        "runId",
                        "sequence",
                        "eventType",
                        "occurredAt",
                        "payload"),
                Set.of(),
                code);
        require(
                value.path("protocolVersion").asText().equals(protocol)
                        && value.path("runId").asText().equals(runId.toString()),
                code);
        require(
                value.get("sequence").isIntegralNumber()
                        && value.get("sequence").canConvertToLong()
                        && value.path("sequence").asLong() >= 1,
                code);
        timestamp(value.get("occurredAt"), code);
        String type = value.path("eventType").asText();
        JsonNode payload = value.get("payload");
        if (type.equals("REVIEW_PASS_UPDATED")) {
            require(protocol.equals("1.1"), code);
            ReviewWireSchemas.validate("review-harness-event-v1.1", value);
            ReviewPassProgress.validate(payload.path("passes"), runId);
            combination(payload, code);
            require(
                    Set.of("RUNNING", "CANCELLING").contains(payload.path("status").asText()),
                    code);
            return value;
        }
        Set<String> required = new HashSet<>(Set.of("status", "resourceState"));
        if (type.equals("REVIEW_RUN_SUCCEEDED")) required.add("result");
        if (type.equals("REVIEW_RUN_FAILED")) required.add("failure");
        if (type.equals("REVIEW_RUN_RESOURCES_RELEASED")) required.add("previousResourceState");
        fields(
                payload,
                required,
                type.equals("REVIEW_RUN_RESOURCES_RELEASED") ? Set.of("recoveryCode") : Set.of(),
                code);
        combination(payload, code);
        String target =
                switch (type) {
                    case "REVIEW_RUN_ACCEPTED" -> "PENDING";
                    case "REVIEW_RUN_STARTED" -> "RUNNING";
                    case "REVIEW_RUN_CANCELLING" -> "CANCELLING";
                    case "REVIEW_RUN_SUCCEEDED" -> "SUCCEEDED";
                    case "REVIEW_RUN_FAILED", "REVIEW_RUN_RESOURCES_RELEASED" -> "FAILED";
                    case "REVIEW_RUN_CANCELLED" -> "CANCELLED";
                    default -> throw new ReviewException(code);
                };
        require(target.equals(payload.path("status").asText()), code);
        if (type.equals("REVIEW_RUN_SUCCEEDED")) descriptor(payload.get("result"), code, protocol);
        if (type.equals("REVIEW_RUN_FAILED")) failure(payload.get("failure"), code);
        if (type.equals("REVIEW_RUN_RESOURCES_RELEASED")) {
            require(
                    payload.path("previousResourceState").asText().equals("UNCERTAIN")
                            && payload.path("resourceState").asText().equals("RELEASED"),
                    code);
            if (payload.has("recoveryCode")) recovery(payload.get("recoveryCode"), code);
        }
        return value;
    }

    public static void follows(JsonNode previous, JsonNode current) {
        String code = "REVIEW_EVENT_INTEGRITY_ERROR", type = current.path("eventType").asText();
        if (previous == null) {
            require(
                    current.path("sequence").asLong() == 1 && type.equals("REVIEW_RUN_ACCEPTED"),
                    code);
            return;
        }
        require(
                current.path("sequence").asLong() == previous.path("sequence").asLong() + 1
                        && current.path("occurredAt")
                                        .asText()
                                        .compareTo(previous.path("occurredAt").asText())
                                >= 0,
                code);
        String source = previous.path("payload").path("status").asText();
        Set<String> allowed =
                switch (type) {
                    case "REVIEW_PASS_UPDATED" -> Set.of("RUNNING", "CANCELLING");
                    case "REVIEW_RUN_STARTED" -> Set.of("PENDING");
                    case "REVIEW_RUN_CANCELLING", "REVIEW_RUN_SUCCEEDED" -> Set.of("RUNNING");
                    case "REVIEW_RUN_FAILED" -> Set.of("PENDING", "RUNNING", "CANCELLING");
                    case "REVIEW_RUN_CANCELLED" -> Set.of("PENDING", "CANCELLING");
                    case "REVIEW_RUN_RESOURCES_RELEASED" -> Set.of("FAILED");
                    default -> Set.of();
                };
        require(allowed.contains(source), code);
        require(previous.path("protocolVersion").equals(current.path("protocolVersion")), code);
        if (type.equals("REVIEW_PASS_UPDATED"))
            require(current.path("payload").path("status").asText().equals(source), code);
        if (type.equals("REVIEW_RUN_RESOURCES_RELEASED"))
            require(
                    previous.path("payload").path("resourceState").asText().equals("UNCERTAIN"),
                    code);
        if (Set.of("REVIEW_RUN_FAILED", "REVIEW_RUN_CANCELLED").contains(type)) {
            require(
                    current.path("payload").path("resourceState").asText().equals("NOT_ACQUIRED")
                            == source.equals("PENDING"),
                    code);
        }
    }

    public static void persisted(ReviewExecutionStore store, ReviewExecution run) {
        if (run.requestBytes() == null) return;
        JsonNode last = null;
        JsonNode lastPasses = null;
        for (long sequence = 1; sequence <= run.eventCursor(); sequence++) {
            JsonNode item = event(store.event(run, sequence), run.runId(), protocolVersion(run));
            follows(last, item);
            if (item.path("eventType").asText().equals("REVIEW_PASS_UPDATED")) {
                ReviewPassProgress.transition(
                        lastPasses,
                        item.path("payload").path("passes"),
                        ReviewJson.parse(run.requestBytes()).path("reviewPlan"));
                lastPasses = item.path("payload").path("passes");
            }
            last = item;
        }
        if (Set.of("COMPLETED", "FAILED", "CANCELLED").contains(run.state())) {
            require(
                    last != null
                            && last.path("payload")
                                    .path("status")
                                    .asText()
                                    .equals(run.pythonStatus())
                            && last.path("payload")
                                    .path("resourceState")
                                    .asText()
                                    .equals(run.resourceState())
                            && !run.blocks(),
                    "REVIEW_HISTORY_CORRUPTED");
            if (run.state().equals("COMPLETED")) {
                require(
                        run.result() != null
                                && last.path("payload").get("result").equals(run.result())
                                && ReviewJson.sha256(store.result(run))
                                        .equals(run.result().path("resultSha256").asText()),
                        "REVIEW_HISTORY_CORRUPTED");
                byte[] original = store.result(run);
                String digest = ReviewJson.sha256(original);
                JsonNode facts =
                        ReviewJson.parse(
                                ReviewJson.canonical(
                                        Map.of(
                                                "result",
                                                run.result(),
                                                "finishedAt",
                                                last.path("occurredAt").asText())));
                result(original, digest, "\"" + digest + "\"", run, facts);
                if (protocolVersion(run).equals("1.1"))
                    ReviewPassProgress.matchesResult(lastPasses, ReviewJson.parse(original));
            }
        }
    }

    public static void result(
            byte[] raw, String contentHash, String etag, ReviewExecution run, JsonNode status) {
        String code = "REVIEW_RESULT_INTEGRITY_ERROR";
        require(raw.length <= 1024 * 1024, code);
        String hash = ReviewJson.sha256(raw);
        require(
                hash.equals(contentHash)
                        && ("\"" + hash + "\"").equals(etag)
                        && hash.equals(status.path("result").path("resultSha256").asText()),
                code);
        JsonNode value = parse(raw, code);
        String expectedVersion =
                ReviewJson.parse(run.requestBytes())
                        .path("executionContract")
                        .path("resultVersion")
                        .asText("1.0");
        if (expectedVersion.equals("3.0")) {
            ReviewResultV3.validate(value);
            require(
                    protocolVersion(run).equals("1.1")
                            && value.path("reviewPlan")
                                    .equals(
                                            ReviewJson.parse(run.requestBytes())
                                                    .path("reviewPlan")),
                    code);
        }
        fields(
                value,
                Set.of(
                        "resultVersion",
                        "resultId",
                        "runId",
                        "snapshotId",
                        "manifestSha256",
                        "generatedAt",
                        "passResults",
                        "evidence",
                        "findings",
                        "reportDraft",
                        "diagnostics"),
                expectedVersion.equals("3.0") ? Set.of("reviewPlan", "coverageStatus") : Set.of(),
                code);
        require(
                value.path("resultVersion").asText().equals(expectedVersion)
                        && value.path("resultVersion")
                                .equals(status.path("result").path("resultVersion"))
                        && value.path("resultId")
                                .asText()
                                .equals(status.path("result").path("resultId").asText())
                        && value.path("runId").asText().equals(run.runId().toString())
                        && value.path("snapshotId").asText().equals(run.snapshotId().toString())
                        && value.path("manifestSha256").asText().equals(run.manifestSha256())
                        && value.path("generatedAt")
                                .asText()
                                .equals(status.path("finishedAt").asText()),
                code);
        timestamp(value.get("generatedAt"), code);
        if (expectedVersion.equals("3.0")) return;
        if (expectedVersion.equals("2.0")) {
            ReviewResultV2.validate(value);
            return;
        }
        require(expectedVersion.equals("1.0"), code);
        for (String array : List.of("passResults", "evidence", "findings"))
            require(value.path(array).isArray() && value.path(array).isEmpty(), code);
        fields(value.get("reportDraft"), Set.of("markdown", "byteSize"), Set.of(), code);
        require(
                value.path("reportDraft").path("markdown").isTextual()
                        && value.path("reportDraft").path("markdown").asText().isEmpty()
                        && value.path("reportDraft").path("byteSize").isIntegralNumber()
                        && value.path("reportDraft").path("byteSize").canConvertToLong()
                        && value.path("reportDraft").path("byteSize").asLong() == 0,
                code);
        fields(value.get("diagnostics"), Set.of("contextManifestArtifactIds"), Set.of(), code);
        require(
                value.path("diagnostics").path("contextManifestArtifactIds").isArray()
                        && value.path("diagnostics").path("contextManifestArtifactIds").isEmpty(),
                code);
    }
}
