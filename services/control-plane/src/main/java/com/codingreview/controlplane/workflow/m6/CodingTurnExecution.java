package com.codingreview.controlplane.workflow.m6;

import com.codingreview.controlplane.session.model.CodingSession;
import com.codingreview.controlplane.session.model.CodingTurn;

/** M6 Turn 编排完成后的 Turn 与可能更新过的 Session。 */
public record CodingTurnExecution(CodingSession session, CodingTurn turn) { }
