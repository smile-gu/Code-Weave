package com.codingreview.controlplane.api.session.dto;

import com.codingreview.controlplane.session.model.CodingTurn;
import java.util.List;

/** Session 多轮历史响应。 */
public record CodingTurnListResponse(List<CodingTurnResponse> turns) {
    /** 将 Turn 领域列表映射为 API 列表。 */
    public static CodingTurnListResponse from(List<CodingTurn> turns) {
        return new CodingTurnListResponse(turns.stream().map(CodingTurnResponse::from).toList());
    }
}
