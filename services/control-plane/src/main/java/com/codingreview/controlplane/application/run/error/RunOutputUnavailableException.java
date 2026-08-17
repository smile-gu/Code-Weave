package com.codingreview.controlplane.application.run.error;

import java.util.Objects;
import java.util.UUID;

/** 指定 Run 存在，但请求的结果尚未产生或无法产生。 */
public class RunOutputUnavailableException extends RuntimeException {

    private final UUID runId;
    private final RunOutputUnavailableReason reason;

    public RunOutputUnavailableException(UUID runId, RunOutputUnavailableReason reason) {
        super(Objects.requireNonNull(reason, "reason").message());
        this.runId = Objects.requireNonNull(runId, "runId");
        this.reason = reason;
    }

    /** 返回无法读取输出的 Run 标识。 */
    public UUID runId() {
        return runId;
    }

    /** 返回本次输出不可用的稳定业务原因。 */
    public RunOutputUnavailableReason reason() {
        return reason;
    }
}
