package com.codingreview.controlplane.session.model;

/** Coding Turn 的业务质量结论。 */
public enum TurnOutcome {
    NO_CHANGES, CHANGES_VERIFIED, VERIFICATION_FAILED, POLICY_VIOLATION,
    WORKSPACE_DRIFT, AGENT_FAILED
}
