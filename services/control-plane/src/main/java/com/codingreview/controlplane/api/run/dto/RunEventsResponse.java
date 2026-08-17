package com.codingreview.controlplane.api.run.dto;

import com.codingreview.controlplane.observability.run.RunEvent;

import java.util.List;
import java.util.UUID;

/** 支持前端按 sequence 增量轮询的事件响应。 */
public record RunEventsResponse(
        UUID runId,
        long afterSequence,
        long nextSequence,
        List<RunEvent> events
) {
    public RunEventsResponse {
        events = List.copyOf(events);
    }

    /** 根据增量事件计算下一次轮询使用的序号游标。 */
    public static RunEventsResponse from(
            UUID runId,
            long afterSequence,
            List<RunEvent> events
    ) {
        long nextSequence = events.isEmpty()
                ? afterSequence : events.getLast().sequence();
        return new RunEventsResponse(runId, afterSequence, nextSequence, events);
    }
}
