package com.yxh.sapvendorportal.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** 向 Portal 返回已脱敏的业务错误摘要，避免前端只能看到 HTTP 状态码。 */
@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handle(ResponseStatusException exception) {
        String message = exception.getReason() == null || exception.getReason().isBlank() ? "请求处理失败。" : exception.getReason();
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of("message", message));
    }
}
