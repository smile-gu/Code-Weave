package com.codingreview.controlplane.legacy.worktreerun.workflow.model;

/** 旧版 Worktree Run 主链路固定阶段及其事件标识。 */
public enum WorktreeRunStage {
    WORKTREE("worktree"),
    CODING("coding"),
    CHANGE_COLLECTION("change-collection"),
    CHANGE_POLICY("change-policy"),
    MAVEN("maven");

    private final String eventName;

    WorktreeRunStage(String eventName) {
        this.eventName = eventName;
    }

    /** 返回该阶段写入事件日志时使用的稳定名称。 */
    public String eventName() {
        return eventName;
    }
}
