package com.codingreview.controlplane.workflow.m0.event;

import com.codingreview.controlplane.event.AgentEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** 将 AgentEvent 投影为大小受控、可持久化的 Run 事件载荷。 */
public final class AgentRunEventMapper {

    public static final int DEFAULT_MAX_PAYLOAD_BYTES = 16 * 1024;

    private final ObjectMapper objectMapper;
    private final int maxPayloadBytes;

    public AgentRunEventMapper(ObjectMapper objectMapper) {
        this(objectMapper, DEFAULT_MAX_PAYLOAD_BYTES);
    }

    public AgentRunEventMapper(ObjectMapper objectMapper, int maxPayloadBytes) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        if (maxPayloadBytes < 1) {
            throw new IllegalArgumentException("maxPayloadBytes must be positive");
        }
        this.maxPayloadBytes = maxPayloadBytes;
    }

    /** 将 Agent 事件映射为带大小控制和摘要的持久化载荷。 */
    public ObjectNode map(AgentEvent event) {
        Objects.requireNonNull(event, "event");
        byte[] serializedPayload;
        try {
            serializedPayload = objectMapper.writeValueAsBytes(event.payload());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize Agent event payload", exception);
        }

        ObjectNode mapped = objectMapper.createObjectNode()
                .put("agentEventType", event.type().name())
                .put("payloadBytes", serializedPayload.length)
                .put("payloadSha256", sha256(serializedPayload));
        if (event.externalId() == null || event.externalId().isBlank()) {
            mapped.putNull("externalId");
        } else {
            mapped.put("externalId", event.externalId());
        }

        if (serializedPayload.length <= maxPayloadBytes) {
            mapped.put("payloadTruncated", false);
            mapped.set("payload", event.payload());
        } else {
            mapped.put("payloadTruncated", true);
            mapped.put("payloadPreview", new String(
                    serializedPayload, 0, maxPayloadBytes, StandardCharsets.UTF_8
            ));
        }
        return mapped;
    }

    /** 计算事件原始载荷的 SHA-256 摘要。 */
    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
