package com.codingreview.controlplane.legacy.worktreerun.api;

import com.codingreview.controlplane.api.common.error.ApiErrorResponse;
import com.codingreview.controlplane.legacy.worktreerun.application.error.RunNotFoundException;
import com.codingreview.controlplane.legacy.worktreerun.application.error.RunOutputUnavailableException;
import com.codingreview.controlplane.legacy.worktreerun.application.error.RunQueueFullException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** 仅为 legacy-worktree-run API 翻译旧版单轮 Run 异常。 */
@Profile("legacy-worktree-run")
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.codingreview.controlplane.legacy.worktreerun")
public class LegacyWorktreeRunExceptionHandler {

    /** 将未知旧版 Run 查询转换为 404 响应。 */
    @ExceptionHandler(RunNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleNotFound(
            RunNotFoundException exception,
            HttpServletRequest request
    ) {
        return response(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND",
                exception.getMessage(), request, exception.runId());
    }

    /** 将旧版 Worktree Run 队列过载转换为 503 响应。 */
    @ExceptionHandler(RunQueueFullException.class)
    ResponseEntity<ApiErrorResponse> handleQueueFull(
            RunQueueFullException exception,
            HttpServletRequest request
    ) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "RUN_QUEUE_FULL",
                exception.getMessage(), request, exception.runId());
    }

    /** 将尚不可读取的旧版 Run 输出转换为 409 响应。 */
    @ExceptionHandler(RunOutputUnavailableException.class)
    ResponseEntity<ApiErrorResponse> handleOutputUnavailable(
            RunOutputUnavailableException exception,
            HttpServletRequest request
    ) {
        return response(HttpStatus.CONFLICT, exception.reason().code(),
                exception.getMessage(), request, exception.runId());
    }

    /** 创建旧版 Worktree Run 使用的统一错误响应。 */
    private ResponseEntity<ApiErrorResponse> response(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request,
            UUID runId
    ) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(
                Instant.now(), status.value(), code, message,
                request.getRequestURI(), runId, Map.of()
        ));
    }
}
