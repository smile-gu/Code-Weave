package com.codingreview.controlplane.api.common.error;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** 所有 HTTP 错误共用的稳定响应结构。 */
public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        UUID runId,
        Map<String, String> fieldErrors
) {
    public ApiErrorResponse {
        fieldErrors = fieldErrors == null ? Map.of() : Map.copyOf(fieldErrors);
    }
}
