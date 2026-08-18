package com.codingreview.controlplane.session.model;

/** Coding Session 自身的持久生命周期状态。 */
public enum CodingSessionStatus {
    INITIALIZING,
    ACTIVE,
    CLOSED,
    FAILED
}
