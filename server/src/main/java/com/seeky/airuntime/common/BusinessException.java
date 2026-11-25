package com.seeky.airuntime.common;

/**
 * 业务异常：携带错误码与给用户看的信息，由 {@link GlobalExceptionHandler} 统一转成 {@link Result}。
 */
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
