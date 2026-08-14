package com.codingreview.controlplane.observability.run;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 追加写入事件日志的不可变事件信封。 */
public record RunEvent(
        int schemaVersion,
        UUID eventId,
        String runId,
        String stage,
        int attempt,
        long sequence,
        Instant timestamp,
        RunEventType type,
        JsonNode payload
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public RunEvent {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported schemaVersion: " + schemaVersion);
        }
        Objects.requireNonNull(eventId, "eventId");
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        if (stage == null || stage.isBlank()) {
            throw new IllegalArgumentException("stage must not be blank");
        }
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be at least 1");
        }
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be at least 1");
        }
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        payload = payload.deepCopy();
    }

    @Override
    public JsonNode payload() {
        return payload.deepCopy();
    }
}
