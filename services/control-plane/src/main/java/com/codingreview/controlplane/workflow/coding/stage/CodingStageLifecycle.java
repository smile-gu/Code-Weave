package com.codingreview.controlplane.workflow.coding.stage;

import com.codingreview.controlplane.observability.run.RunEventJournal;
import com.codingreview.controlplane.observability.run.RunEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.EnumMap;
import java.util.Objects;

/** 每轮独立记录阶段事实，写入不确定后停止推进，不承担任务终态裁决。 */
public final class CodingStageLifecycle {
    private enum State { STARTED, COMPLETED, FAILED, SKIPPED }
    private final String turnId;
    private final RunEventJournal journal;
    private final ObjectMapper mapper;
    private final EnumMap<CodingStage, State> states = new EnumMap<>(CodingStage.class);
    private CodingStage current;
    private CodingStage attempted;
    private volatile boolean writeFailed;

    /** 为一个 Turn 创建没有跨轮共享状态的阶段记录器。 */
    public CodingStageLifecycle(String turnId, RunEventJournal journal, ObjectMapper mapper) {
        this.turnId = Objects.requireNonNull(turnId);
        this.journal = Objects.requireNonNull(journal);
        this.mapper = Objects.requireNonNull(mapper);
    }

    /** 先可靠记录开始事实，再允许调用方执行阶段工作。 */
    public void start(CodingStage stage) {
        if (current != null || states.containsKey(stage)) throw new IllegalStateException("阶段不能重复或并行开始");
        attempted = stage;
        append(stage, RunEventType.STAGE_STARTED, payload("正在执行" + stage.label() + "。"));
        states.put(stage, State.STARTED);
        current = stage;
    }

    /** 仅结束当前已开始阶段，日志失败时不能启动下一阶段。 */
    public void complete(String message) {
        requireCurrent();
        append(current, RunEventType.STAGE_COMPLETED, payload(message));
        states.put(current, State.COMPLETED);
        current = null;
        attempted = null;
    }

    /** 记录当前阶段未达目标及稳定失败分类。 */
    public void fail(String code, String kind, String message, String causeCode) {
        requireCurrent();
        ObjectNode payload = payload(message).put("failureCode", code).put("failureKind", kind);
        if (causeCode != null) payload.put("causeCode", causeCode);
        append(current, RunEventType.STAGE_FAILED, payload);
        states.put(current, State.FAILED);
        current = null;
        attempted = null;
    }

    /** 为所有尚未开始的阶段记录明确跳过原因。 */
    public void skipRemaining(String reasonCode, String message, CodingStage causedBy) {
        if (current != null) throw new IllegalStateException("活动阶段不能记为跳过");
        for (CodingStage stage : CodingStage.values()) {
            if (states.containsKey(stage)) continue;
            ObjectNode payload = payload(message).put("reasonCode", reasonCode);
            if (causedBy != null) payload.put("causedByStage", causedBy.id());
            append(stage, RunEventType.STAGE_SKIPPED, payload);
            states.put(stage, State.SKIPPED);
        }
    }

    /** 返回当前阶段或尚未成功记录开始的目标阶段。 */
    public CodingStage current() { return current != null ? current : attempted; }
    /** 判断当前日志是否已经出现不确定写入。 */
    public boolean writeFailed() { return writeFailed; }
    /** 阻止已知事实日志故障后的继续追加。 */
    public void markWriteFailed() { writeFailed = true; }
    /** 校验当前确实存在活动阶段。 */
    private void requireCurrent() {
        if (current == null) throw new IllegalStateException("没有可结束的活动阶段");
    }
    /** 生成带版本号的小型阶段说明。 */
    private ObjectNode payload(String message) {
        return mapper.createObjectNode().put("stageProtocolVersion", 1).put("message", message);
    }
    /** 包装不确定写入并封闭本轮生命周期记录器。 */
    private void append(CodingStage stage, RunEventType type, ObjectNode payload) {
        if (writeFailed) throw new StageEventPersistenceException(null);
        try {
            journal.append(turnId, stage.id(), 1, type, payload);
        } catch (RuntimeException exception) {
            writeFailed = true;
            throw new StageEventPersistenceException(exception);
        }
    }

    /** 标识阶段事实未确认落盘，禁止尝试补造后续事实。 */
    public static final class StageEventPersistenceException extends RuntimeException {
        public StageEventPersistenceException(Throwable cause) { super("阶段事实无法可靠保存。", cause); }
    }
}
