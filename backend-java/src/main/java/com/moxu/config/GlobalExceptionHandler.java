package com.moxu.config;

import com.moxu.llm.LlmHttpException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(Map.of("detail", safeMessage(exception.getReason(), "Request failed")));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleValidation(MethodArgumentNotValidException exception) {
        String detail = "Validation failed";
        FieldError fieldError = exception.getBindingResult().getFieldError();
        if (fieldError != null) {
            detail = fieldError.getField() + ": " + fieldError.getDefaultMessage();
        }
        return Map.of("detail", detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleBadRequest(HttpMessageNotReadableException exception) {
        return Map.of("detail", "Invalid request body");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleIllegalArgument(IllegalArgumentException exception) {
        return Map.of("detail", safeMessage(exception.getMessage(), "Invalid request"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, Object> handleNoResourceFound(NoResourceFoundException exception) {
        return Map.of("detail", "Resource not found");
    }

    /** LLM 上游错误不经包装直达这里（chatOnce 路径）。 */
    @ExceptionHandler(LlmHttpException.class)
    public ResponseEntity<Map<String, Object>> handleLlmHttp(LlmHttpException exception) {
        return llmErrorResponse(exception.getStatusCode());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnhandled(Exception exception, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}: {}",
                request.getMethod(),
                request.getRequestURI(),
                exception.getMessage(),
                exception);

        // LLM 错误常被重试层包成 RuntimeException，沿 cause 链找出真实上游状态，
        // 否则前端只能看到笼统的 500，不知道是限流还是余额不足
        Throwable deepest = exception;
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof LlmHttpException llm) {
                return llmErrorResponse(llm.getStatusCode());
            }
            deepest = cause;
        }

        String message = deepest.getMessage();
        if (message == null || message.isBlank()) message = exception.getMessage();
        if (message == null) message = "";
        if (message.contains("Failed to connect")
                || message.contains("ConnectException")
                || message.contains("UnknownHostException")) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("detail", "无法连接模型接口：请检查 Base URL、网络或代理配置。"));
        }
        if (message.contains("timeout") || message.contains("timed out")) {
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                    .body(Map.of("detail", "模型接口响应超时：上游过慢或渠道不稳定，请稍后重试。"));
        }

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("detail", "Internal server error"));
    }

    private ResponseEntity<Map<String, Object>> llmErrorResponse(int upstreamStatus) {
        String detail = switch (upstreamStatus) {
            case 400 -> "上游接口返回 400：请求格式不被接受，通常是模型名或参数与该渠道不兼容。";
            case 401 -> "上游接口返回 401：API Key 无效，或当前渠道不接受这把 Key。";
            case 402 -> "上游接口返回 402：账户余额不足。";
            case 403 -> "上游接口返回 403：当前 Key 没有权限访问该模型，或该渠道禁用了此模型。";
            case 404 -> "上游接口返回 404：Base URL、接口路径或模型名不匹配。";
            case 429 -> "上游接口返回 429：触发限流或额度不足，稍后再试，或在「分工」里给该角色换个模型。";
            default -> upstreamStatus >= 500
                    ? "上游接口返回 " + upstreamStatus + "：渠道服务端故障，请稍后重试。"
                    : "上游接口返回 " + upstreamStatus + "：模型调用被拒绝。";
        };
        HttpStatus status = upstreamStatus == 429 ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(Map.of("detail", detail));
    }

    private String safeMessage(String message, String fallback) {
        return message == null || message.isBlank() ? fallback : message;
    }
}
