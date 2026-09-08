package com.codingreview.controlplane.api.common.error;

import com.codingreview.controlplane.application.session.error.CodingSessionCreationException;
import com.codingreview.controlplane.application.session.error.CodingSessionNotFoundException;
import com.codingreview.controlplane.application.session.error.CodingSessionConflictException;
import com.codingreview.controlplane.runtime.readiness.error.RuntimeNotReadyException;
import com.codingreview.controlplane.session.persistence.CodingTurnHistoryException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 将内部异常翻译为前端可理解、且不泄露技术诊断的错误响应。 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 将请求体字段校验失败转换为带字段详情的 400 响应。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "提交参数不符合要求，请检查标记字段。", request, null, fields);
    }

    /** 将方法参数约束失败转换为 400 响应。 */
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiErrorResponse> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request
    ) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                exception.getMessage(), request, null, Map.of());
    }

    /** 将请求格式和参数类型错误统一转换为 400 响应。 */
    @ExceptionHandler({
            IllegalArgumentException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<ApiErrorResponse> handleBadRequest(
            Exception exception,
            HttpServletRequest request
    ) {
        String message = exception instanceof IllegalArgumentException
                ? exception.getMessage()
                : "请求格式无法解析，请检查 JSON、任务标识和参数类型。";
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                message, request, null, Map.of());
    }

    /** 将未知 Coding Session 查询转换为 404 响应。 */
    @ExceptionHandler(CodingSessionNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleSessionNotFound(
            CodingSessionNotFoundException exception,
            HttpServletRequest request
    ) {
        return response(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND",
                exception.getMessage(), request, null, Map.of());
    }

    /** 将 Session 初始化失败转换为可操作的 422 响应。 */
    @ExceptionHandler(CodingSessionCreationException.class)
    ResponseEntity<ApiErrorResponse> handleSessionCreation(
            CodingSessionCreationException exception,
            HttpServletRequest request
    ) {
        LOGGER.error("Coding Session 创建失败，sessionId={}，code={}，diagnostic={}",
                exception.sessionId(), exception.code(), exception.diagnostic(), exception);
        return response(HttpStatus.UNPROCESSABLE_ENTITY, exception.code(),
                exception.userMessage(), request, null, Map.of());
    }

    /** 将 Session 当前状态冲突转换为可重试或可操作的 409 响应。 */
    @ExceptionHandler(CodingSessionConflictException.class)
    ResponseEntity<ApiErrorResponse> handleSessionConflict(
            CodingSessionConflictException exception,
            HttpServletRequest request
    ) {
        return response(HttpStatus.CONFLICT, exception.code(),
                exception.getMessage(), request, null, Map.of());
    }

    /** 将不可读取的 Turn 历史转换为明确的会话隔离错误。 */
    @ExceptionHandler(CodingTurnHistoryException.class)
    ResponseEntity<ApiErrorResponse> handleTurnHistoryFailure(
            CodingTurnHistoryException exception,
            HttpServletRequest request
    ) {
        LOGGER.error("Coding Turn 历史不可读取，sessionId={}, kind={}, path={}",
                exception.sessionId(), exception.kind(), exception.file(), exception);
        return response(HttpStatus.CONFLICT, "TURN_HISTORY_" + exception.kind().name(),
                "当前会话的 Coding 历史无法可靠读取，已停止继续执行，请检查服务日志。",
                request, null, Map.of());
    }

    /** 将本机环境未就绪转换为不创建 Run 的 503 响应。 */
    @ExceptionHandler(RuntimeNotReadyException.class)
    ResponseEntity<ApiErrorResponse> handleRuntimeNotReady(
            RuntimeNotReadyException exception,
            HttpServletRequest request
    ) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "RUNTIME_NOT_READY",
                exception.getMessage(), request, null, Map.of());
    }

    /** 记录未分类异常并返回不泄露诊断的 500 响应。 */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> handleUnexpected(
            Exception exception,
            HttpServletRequest request
    ) {
        LOGGER.error("未处理的 API 异常，path={}", request.getRequestURI(), exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "服务暂时无法完成请求，请查看服务日志后重试。", request, null, Map.of());
    }

    /** 创建统一结构的 API 错误响应。 */
    private ResponseEntity<ApiErrorResponse> response(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request,
            UUID runId,
            Map<String, String> fieldErrors
    ) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(
                Instant.now(),
                status.value(),
                code,
                message,
                request.getRequestURI(),
                runId,
                fieldErrors
        ));
    }
}
