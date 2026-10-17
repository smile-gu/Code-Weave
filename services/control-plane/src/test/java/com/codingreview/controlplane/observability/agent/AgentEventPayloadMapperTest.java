package com.codingreview.controlplane.observability.agent;

import com.codingreview.controlplane.event.AgentEvent;
import com.codingreview.controlplane.event.AgentEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.assertj.core.api.Assertions.assertThat;

/** 验证统一载荷协议的字节边界、身份和输入隔离。 */
class AgentEventPayloadMapperTest {
    private final ObjectMapper mapper = new ObjectMapper();

    /** 阈值以内保留完整载荷，超过阈值只提供文本预览。 */
    @Test
    void preservesExactThresholdAndOriginalByteDigest() throws Exception {
        var payload = mapper.createObjectNode().put("text", "中文😀");
        byte[] bytes = mapper.writeValueAsBytes(payload);
        for (int limit : new int[]{bytes.length - 1, bytes.length, bytes.length + 1}) {
            var result = new AgentEventPayloadMapper(mapper, limit).map(
                    new AgentEvent(AgentEventType.MESSAGE_COMPLETED, "  original-id  ", payload));
            assertThat(result.path("agentProtocolVersion").asInt()).isEqualTo(1);
            assertThat(result.has("eventType")).isFalse();
            assertThat(result.path("externalId").asText()).isEqualTo("  original-id  ");
            assertThat(result.path("payloadBytes").asInt()).isEqualTo(bytes.length);
            assertThat(result.path("payloadSha256").asText()).isEqualTo(
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            assertThat(result.path("payloadTruncated").asBoolean()).isEqualTo(limit < bytes.length);
            assertThat(result.has("payload")).isEqualTo(limit >= bytes.length);
        }
    }

    /** 每个截断位置都保持合法字符边界且不超过字节限制。 */
    @Test
    void truncatesChineseAndEmojiWithoutReplacementCharacters() throws Exception {
        var payload = mapper.createObjectNode().put("text", "中文😀结束");
        byte[] bytes = mapper.writeValueAsBytes(payload);
        String original = new String(bytes, StandardCharsets.UTF_8);
        for (int limit = 1; limit < bytes.length; limit++) {
            var result = new AgentEventPayloadMapper(mapper, limit).map(
                    new AgentEvent(AgentEventType.MESSAGE_COMPLETED, null, payload));
            String preview = result.path("payloadPreview").asText();
            assertThat(original).startsWith(preview);
            assertThat(preview).doesNotContain("\uFFFD");
            assertThat(preview.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(limit);
            assertThat(result.has("payload")).isFalse();
            assertThat(result.path("externalId").isNull()).isTrue();
        }
    }

    /** 映射不修改输入，也不与输出共享可变载荷。 */
    @Test
    void preservesBlankIdAndDoesNotMutateInput() {
        var payload = mapper.createObjectNode().put("text", "original");
        var result = new AgentEventPayloadMapper(mapper).map(
                new AgentEvent(AgentEventType.MESSAGE_COMPLETED, "", payload));
        assertThat(result.path("externalId").asText()).isEmpty();
        ((com.fasterxml.jackson.databind.node.ObjectNode) result.path("payload")).put("text", "changed");
        assertThat(payload.path("text").asText()).isEqualTo("original");
    }
}
