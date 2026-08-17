package com.codingreview.controlplane.observability.run;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** Run 事件的持久化事实源，负责分配连续序号并支持断点重放。 */
public interface RunEventJournal {

    RunEvent append(String runId, String stage, int attempt, RunEventType type, JsonNode payload);

    List<RunEvent> readAfter(String runId, long afterSequence);
}
