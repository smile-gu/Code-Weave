package com.codingreview.controlplane.adapter.claude;

import com.codingreview.controlplane.event.AgentEvent;

/** Claude 运行期间的实时事件接收端，不绑定具体的持久化或推送实现。 */
@FunctionalInterface
public interface AgentEventSink {

    /** 接收一条已经转换为平台统一格式的 Agent 事件。 */
    void onEvent(AgentEvent event);
}
