package com.codingreview.controlplane.application.run.error;

import java.util.UUID;

/** 本机 Run 执行队列已达到配置上限。 */
public class RunQueueFullException extends RuntimeException {

    private final UUID runId;

    public RunQueueFullException(UUID runId, Throwable cause) {
        super("当前任务队列已满，请等待正在执行的任务结束后重试。", cause);
        this.runId = runId;
    }

    public UUID runId() {
        return runId;
    }
}
