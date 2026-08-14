package com.codingreview.controlplane.workflow.m0.model;

/** M0 主链路固定阶段及其事件标识。 */
public enum M0RunStage {
    WORKTREE("worktree"),
    CODING("coding"),
    CHANGE_COLLECTION("change-collection"),
    CHANGE_POLICY("change-policy"),
    MAVEN("maven");

    private final String eventName;

    M0RunStage(String eventName) {
        this.eventName = eventName;
    }

    public String eventName() {
        return eventName;
    }
}
