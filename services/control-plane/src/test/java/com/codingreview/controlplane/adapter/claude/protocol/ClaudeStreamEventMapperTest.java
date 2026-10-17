package com.codingreview.controlplane.adapter.claude.protocol;

import com.codingreview.controlplane.event.AgentEvent;
import com.codingreview.controlplane.event.AgentEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证 Claude Code 协议事件能否被可靠地映射或拒绝。 */
class ClaudeStreamEventMapperTest {

    private final ClaudeStreamEventMapper mapper = new ClaudeStreamEventMapper(new ObjectMapper());

    @Test
    void mapsRealToolUseAndToolFailureEvents() {
        String toolUse = """
                {"type":"assistant","uuid":"message-1","message":{"content":[{"type":"tool_use","id":"call-1","name":"Read","input":{"file_path":"demo.txt"}}]}}
                """;
        String toolResult = """
                {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"call-1","is_error":true,"content":"missing"}]}}
                """;

        List<AgentEvent> started = mapper.mapLine(toolUse);
        List<AgentEvent> failed = mapper.mapLine(toolResult);

        assertThat(started).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(AgentEventType.TOOL_CALL_STARTED);
            assertThat(event.externalId()).isEqualTo("call-1");
        });
        assertThat(failed).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(AgentEventType.TOOL_CALL_FAILED);
            assertThat(event.externalId()).isEqualTo("call-1");
        });
    }

    @Test
    void ignoresThinkingContent() {
        String thinking = """
                {"type":"assistant","message":{"content":[{"type":"thinking","thinking":"private reasoning"}]}}
                """;

        assertThat(mapper.mapLine(thinking)).isEmpty();
    }

    @Test
    void ignoresIntermediateThinkingTokenUpdates() {
        String usageUpdate = """
                {"type":"system","subtype":"thinking_tokens","estimated_tokens":42,"estimated_tokens_delta":1,"uuid":"usage-1","session_id":"s-1"}
                """;

        assertThat(mapper.mapLine(usageUpdate)).isEmpty();
    }

    @Test
    void preservesExactUsageInTerminalResult() {
        String terminalResult = """
                {"type":"result","subtype":"success","is_error":false,"session_id":"s-1","total_cost_usd":0.110062,"usage":{"input_tokens":6286,"output_tokens":2469,"cache_read_input_tokens":16384}}
                """;

        assertThat(mapper.mapLine(terminalResult)).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(AgentEventType.RUNTIME_COMPLETED);
            assertThat(event.payload().path("total_cost_usd").decimalValue())
                    .isEqualByComparingTo("0.110062");
            assertThat(event.payload().path("usage").path("input_tokens").asInt()).isEqualTo(6286);
            assertThat(event.payload().path("usage").path("output_tokens").asInt()).isEqualTo(2469);
            assertThat(event.payload().path("usage").path("cache_read_input_tokens").asInt())
                    .isEqualTo(16384);
        });
    }

    @Test
    void rejectsMalformedProtocolLines() {
        assertThatThrownBy(() -> mapper.mapLine("not-json"))
                .isInstanceOf(ClaudeStreamProtocolException.class);
    }
}
