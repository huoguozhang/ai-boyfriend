package com.seeky.airuntime.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebInputException;

/**
 * 全局异常处理：所有异常都收敛成 {@link Result} 信封，HTTP 状态保持 200。
 *
 * <p>与 seeky-app-server 的约定一致——前端只看 {@code code}，不看 HTTP 状态码，
 * 这样网关/负载均衡不会把业务失败当传输失败重试。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException ex) {
        log.warn("业务异常 code={} msg={}", ex.getCode(), ex.getMessage());
        return Result.error(ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public Result<Void> handleValidation(WebExchangeBindException ex) {
        String detail = ex.getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse(ErrorCode.PARAM_VALIDATION_FAILED_MSG);
        return Result.error(ErrorCode.PARAM_VALIDATION_FAILED, detail);
    }

    @ExceptionHandler(ServerWebInputException.class)
    public Result<Void> handleBadBody(ServerWebInputException ex) {
        return Result.error(ErrorCode.REQUEST_BODY_INVALID, ex.getReason());
    }

    @ExceptionHandler(Throwable.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleUnexpected(Throwable ex) {
        log.error("未预期异常", ex);
        return Result.error(ErrorCode.SYSTEM_ERROR, ErrorCode.SYSTEM_ERROR_MSG);
    }
}
