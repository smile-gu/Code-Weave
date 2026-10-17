package com.codingreview.controlplane.session.model;

/** Coding Turn 的执行生命周期，与质量结论分开表达。 */
public enum TurnStatus {
    QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED, INTERRUPTED
}
