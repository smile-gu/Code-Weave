package com.codingreview.controlplane.event;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/** 平台内部统一的 Agent 事件，其中 payload 保留原始协议数据以便观测与追踪。 */
public record AgentEvent(
        AgentEventType type,
        String externalId,
        JsonNode payload
) {
    public AgentEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        payload = payload.deepCopy();
    }

    @Override
    public JsonNode payload() {
        return payload.deepCopy();
    }
}
