package com.codingreview.controlplane.legacy.worktreerun.workflow.event;

import com.codingreview.controlplane.event.AgentEvent;
import com.codingreview.controlplane.event.AgentEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证 Agent 事件进入 Run 日志前的完整保留、限长和完整性摘要。 */
@Tag("legacy-worktree-run")
class AgentRunEventMapperTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void keepsPayloadWhenItFitsWithinLimit() throws Exception {
        ObjectNode payload = objectMapper.createObjectNode().put("message", "done");
        AgentRunEventMapper mapper = new AgentRunEventMapper(objectMapper, 1024);

        ObjectNode mapped = mapper.map(new AgentEvent(
                AgentEventType.MESSAGE_COMPLETED, "message-1", payload
        ));

        byte[] serialized = objectMapper.writeValueAsBytes(payload);
        assertThat(mapped.path("agentEventType").asText()).isEqualTo("MESSAGE_COMPLETED");
        assertThat(mapped.path("externalId").asText()).isEqualTo("message-1");
        assertThat(mapped.path("payloadBytes").asInt()).isEqualTo(serialized.length);
        assertThat(mapped.path("payloadTruncated").asBoolean()).isFalse();
        assertThat(mapped.path("payload")).isEqualTo(payload);
        assertThat(mapped.path("payloadSha256").asText()).isEqualTo(sha256(serialized));
    }

    @Test
    void storesPreviewAndDigestWhenPayloadExceedsLimit() throws Exception {
        ObjectNode payload = objectMapper.createObjectNode().put("message", "0123456789abcdef");
        byte[] serialized = objectMapper.writeValueAsBytes(payload);
        AgentRunEventMapper mapper = new AgentRunEventMapper(objectMapper, 12);

        ObjectNode mapped = mapper.map(new AgentEvent(
                AgentEventType.TOOL_CALL_COMPLETED, null, payload
        ));

        assertThat(mapped.path("externalId").isNull()).isTrue();
        assertThat(mapped.path("payloadTruncated").asBoolean()).isTrue();
        assertThat(mapped.has("payload")).isFalse();
        assertThat(mapped.path("payloadPreview").asText())
                .isEqualTo(new String(serialized, 0, 12, StandardCharsets.UTF_8));
        assertThat(mapped.path("payloadBytes").asInt()).isEqualTo(serialized.length);
        assertThat(mapped.path("payloadSha256").asText()).isEqualTo(sha256(serialized));
    }

    @Test
    void rejectsNonPositivePayloadLimit() {
        assertThatThrownBy(() -> new AgentRunEventMapper(objectMapper, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxPayloadBytes");
    }

    private String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
