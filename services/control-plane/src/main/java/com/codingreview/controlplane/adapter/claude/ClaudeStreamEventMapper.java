package com.codingreview.controlplane.adapter.claude;

import com.codingreview.controlplane.event.AgentEvent;
import com.codingreview.controlplane.event.AgentEventType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** 将 Claude Code 的单行 NDJSON 事件转换为平台统一事件。 */
public final class ClaudeStreamEventMapper {

    private final ObjectMapper objectMapper;

    public ClaudeStreamEventMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 解析一行协议数据；无须持久化的事件返回空列表。 */
    public List<AgentEvent> mapLine(String line) {
        final JsonNode root;
        try {
            root = objectMapper.readTree(line);
        } catch (JsonProcessingException exception) {
            throw new ClaudeStreamProtocolException("Invalid Claude stream JSON", exception);
        }

        String type = root.path("type").asText();
        String subtype = root.path("subtype").asText();
        if ("system".equals(type) && "init".equals(subtype)) {
            return List.of(event(AgentEventType.RUNTIME_INITIALIZED, root.path("session_id").asText(null), root));
        }
        if ("system".equals(type) && "thinking_tokens".equals(subtype)) {
            return List.of(event(AgentEventType.USAGE_UPDATED, root.path("uuid").asText(null), root));
        }
        if ("assistant".equals(type)) {
            return mapAssistant(root);
        }
        if ("user".equals(type)) {
            return mapToolResults(root);
        }
        if ("result".equals(type)) {
            boolean failed = root.path("is_error").asBoolean(false) || !"success".equals(subtype);
            return List.of(event(
                    failed ? AgentEventType.RUNTIME_FAILED : AgentEventType.RUNTIME_COMPLETED,
                    root.path("session_id").asText(null),
                    root
            ));
        }
        return List.of();
    }

    private List<AgentEvent> mapAssistant(JsonNode root) {
        List<AgentEvent> events = new ArrayList<>();
        for (JsonNode content : root.path("message").path("content")) {
            switch (content.path("type").asText()) {
                case "text" -> events.add(event(AgentEventType.MESSAGE_COMPLETED, root.path("uuid").asText(null), content));
                case "tool_use" -> events.add(event(AgentEventType.TOOL_CALL_STARTED, content.path("id").asText(null), content));
                default -> {
                    // Thinking 等内部内容按约定不持久化、不展示。
                }
            }
        }
        return events;
    }

    private List<AgentEvent> mapToolResults(JsonNode root) {
        List<AgentEvent> events = new ArrayList<>();
        for (JsonNode content : root.path("message").path("content")) {
            if (!"tool_result".equals(content.path("type").asText())) {
                continue;
            }
            AgentEventType type = content.path("is_error").asBoolean(false)
                    ? AgentEventType.TOOL_CALL_FAILED
                    : AgentEventType.TOOL_CALL_COMPLETED;
            events.add(event(type, content.path("tool_use_id").asText(null), content));
        }
        return events;
    }

    private AgentEvent event(AgentEventType type, String externalId, JsonNode payload) {
        return new AgentEvent(type, externalId, payload.deepCopy());
    }
}
