package com.codingreview.controlplane.api.session.dto;

import com.codingreview.controlplane.session.model.CodingSession;

import java.util.List;

/** 最近 Coding Session 列表响应。 */
public record CodingSessionListResponse(
        List<CodingSessionResponse> sessions
) {
    /** 将领域列表转换为只读响应列表。 */
    public static CodingSessionListResponse from(List<CodingSession> sessions) {
        return new CodingSessionListResponse(
                sessions.stream().map(CodingSessionResponse::from).toList()
        );
    }
}
