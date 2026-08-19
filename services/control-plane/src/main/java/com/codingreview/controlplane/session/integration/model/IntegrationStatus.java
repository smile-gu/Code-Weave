package com.codingreview.controlplane.session.integration.model;

/** Session 写回原仓库时的持久生命周期。 */
public enum IntegrationStatus {
    MERGING,
    MERGED,
    INTERRUPTED,
    NEEDS_ATTENTION
}
