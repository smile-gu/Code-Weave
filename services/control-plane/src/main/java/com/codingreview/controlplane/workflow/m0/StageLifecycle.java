package com.codingreview.controlplane.workflow.m0;

import com.codingreview.controlplane.observability.run.RunEventJournal;
import com.codingreview.controlplane.observability.run.RunEventType;
import com.codingreview.controlplane.workflow.m0.model.M0RunFailureCode;
import com.codingreview.controlplane.workflow.m0.model.M0RunStage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

/** 统一发布 M0 阶段开始、完成和失败事件，不决定业务流转。 */
final class StageLifecycle {

    private static final int FIRST_ATTEMPT = 1;

    private final RunEventJournal eventJournal;
    private final ObjectMapper objectMapper;

    StageLifecycle(RunEventJournal eventJournal, ObjectMapper objectMapper) {
        this.eventJournal = Objects.requireNonNull(eventJournal, "eventJournal");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
    }

    void started(String runId, M0RunStage stage, ObjectNode details) {
        append(runId, stage, RunEventType.STAGE_STARTED, details);
    }

    void completed(String runId, M0RunStage stage, ObjectNode details) {
        append(runId, stage, RunEventType.STAGE_COMPLETED, details);
    }

    void failed(
            String runId,
            M0RunStage stage,
            M0RunFailureCode failureCode,
            String userMessage,
            String diagnostic
    ) {
        failed(runId, stage, failureCode, userMessage, diagnostic, null);
    }

    void failed(
            String runId,
            M0RunStage stage,
            M0RunFailureCode failureCode,
            String userMessage,
            String diagnostic,
            ObjectNode details
    ) {
        ObjectNode payload = objectMapper.createObjectNode()
                .put("failureCode", failureCode.name())
                .put("message", userMessage);
        if (diagnostic == null || diagnostic.isBlank()) {
            payload.putNull("diagnostic");
        } else {
            payload.put("diagnostic", diagnostic);
        }
        if (details != null) {
            payload.set("details", details);
        }
        append(runId, stage, RunEventType.STAGE_FAILED, payload);
    }

    private void append(String runId, M0RunStage stage, RunEventType type, ObjectNode details) {
        eventJournal.append(
                runId,
                stage.eventName(),
                FIRST_ATTEMPT,
                type,
                details == null ? objectMapper.createObjectNode() : details
        );
    }
}
