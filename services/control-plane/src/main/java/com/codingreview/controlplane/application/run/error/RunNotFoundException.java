package com.codingreview.controlplane.application.run.error;

import java.util.UUID;

/** 当前进程中不存在指定 Run。 */
public class RunNotFoundException extends RuntimeException {

    private final UUID runId;

    public RunNotFoundException(UUID runId) {
        super("找不到指定任务。任务可能不存在，或服务重启后内存状态已丢失。");
        this.runId = runId;
    }

    /** 返回未找到的 Run 标识。 */
    public UUID runId() {
        return runId;
    }
}
