package com.codingreview.controlplane.event;

import com.fasterxml.jackson.databind.JsonNode;

public record AgentEvent(
        AgentEventType type,
        String externalId,
        JsonNode payload
) {
}

