package com.yxh.sapvendorportal.common.exception;

import org.springframework.http.ResponseEntity;
import com.yxh.sapvendorportal.common.result.ApiErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** 向 Portal 返回已脱敏的业务错误摘要，避免前端只能看到 HTTP 状态码。 */
@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handle(ResponseStatusException exception) {
        String message = exception.getReason() == null || exception.getReason().isBlank() ? "请求处理失败。" : exception.getReason();
        return ResponseEntity.status(exception.getStatusCode()).body(new ApiErrorResponse(message));
    }
}
