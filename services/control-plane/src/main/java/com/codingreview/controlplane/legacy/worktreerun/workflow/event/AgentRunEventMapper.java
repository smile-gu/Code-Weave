package com.codingreview.controlplane.legacy.worktreerun.workflow.event;

import com.codingreview.controlplane.event.AgentEvent;
import com.codingreview.controlplane.observability.agent.AgentEventPayloadMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 保留旧版调用入口，载荷协议统一委托共享映射器。 */
public final class AgentRunEventMapper {
    public static final int DEFAULT_MAX_PAYLOAD_BYTES = AgentEventPayloadMapper.DEFAULT_MAX_PAYLOAD_BYTES;
    private final AgentEventPayloadMapper delegate;

    /** 使用默认载荷限制创建旧版适配器。 */
    public AgentRunEventMapper(ObjectMapper mapper) {
        this(mapper, DEFAULT_MAX_PAYLOAD_BYTES);
    }

    /** 使用指定载荷限制创建旧版适配器。 */
    public AgentRunEventMapper(ObjectMapper mapper, int maxPayloadBytes) {
        delegate = new AgentEventPayloadMapper(mapper, maxPayloadBytes);
    }

    /** 按共享协议生成可持久化的 Agent 载荷。 */
    public ObjectNode map(AgentEvent event) {
        return delegate.map(event);
    }
}
