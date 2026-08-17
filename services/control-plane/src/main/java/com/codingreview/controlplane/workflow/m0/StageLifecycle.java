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

    /** 发布指定阶段的开始事件。 */
    void started(String runId, M0RunStage stage, ObjectNode details) {
        append(runId, stage, RunEventType.STAGE_STARTED, details);
    }

    /** 发布指定阶段的完成事件。 */
    void completed(String runId, M0RunStage stage, ObjectNode details) {
        append(runId, stage, RunEventType.STAGE_COMPLETED, details);
    }

    /** 发布不包含额外详情的阶段失败事件。 */
    void failed(
            String runId,
            M0RunStage stage,
            M0RunFailureCode failureCode,
            String userMessage,
            String diagnostic
    ) {
        failed(runId, stage, failureCode, userMessage, diagnostic, null);
    }

    /** 发布包含结构化详情的阶段失败事件。 */
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

    /** 将阶段生命周期事件追加到 Run 事实日志。 */
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
