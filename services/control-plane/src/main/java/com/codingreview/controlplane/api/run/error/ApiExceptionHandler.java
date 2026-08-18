package com.codingreview.controlplane.api.run.error;

import com.codingreview.controlplane.application.run.error.RunNotFoundException;
import com.codingreview.controlplane.application.run.error.RunOutputUnavailableException;
import com.codingreview.controlplane.application.run.error.RunQueueFullException;
import com.codingreview.controlplane.application.session.error.CodingSessionCreationException;
import com.codingreview.controlplane.application.session.error.CodingSessionNotFoundException;
import com.codingreview.controlplane.runtime.readiness.error.RuntimeNotReadyException;
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

    /** 将未知 Run 查询转换为 404 响应。 */
    @ExceptionHandler(RunNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleNotFound(
            RunNotFoundException exception,
            HttpServletRequest request
    ) {
        return response(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND",
                exception.getMessage(), request, exception.runId(), Map.of());
    }

    /** 将执行队列过载转换为 503 响应。 */
    @ExceptionHandler(RunQueueFullException.class)
    ResponseEntity<ApiErrorResponse> handleQueueFull(
            RunQueueFullException exception,
            HttpServletRequest request
    ) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "RUN_QUEUE_FULL",
                exception.getMessage(), request, exception.runId(), Map.of());
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

    /** 将本机环境未就绪转换为不创建 Run 的 503 响应。 */
    @ExceptionHandler(RuntimeNotReadyException.class)
    ResponseEntity<ApiErrorResponse> handleRuntimeNotReady(
            RuntimeNotReadyException exception,
            HttpServletRequest request
    ) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "RUNTIME_NOT_READY",
                exception.getMessage(), request, null, Map.of());
    }

    /** 将尚不可读取的 Run 输出转换为 409 响应。 */
    @ExceptionHandler(RunOutputUnavailableException.class)
    ResponseEntity<ApiErrorResponse> handleOutputUnavailable(
            RunOutputUnavailableException exception,
            HttpServletRequest request
    ) {
        return response(HttpStatus.CONFLICT, exception.reason().code(),
                exception.getMessage(), request, exception.runId(), Map.of());
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
