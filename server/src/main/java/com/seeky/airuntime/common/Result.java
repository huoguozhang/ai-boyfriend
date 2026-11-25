package com.seeky.airuntime.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一业务响应包装（与 seeky-app-server 的 {@code com.seeky.common.Result} 完全一致）。
 *
 * <p>约定：{@code code=0} 等价于 {@code success=true}，非 0 均为业务异常（与 HTTP 200 无关）；
 * {@code data} 在失败时为 null；{@code errMsg} 是接口/异常错误信息，{@code userMsg} 是给用户看的
 * 友好提示（前端直接弹 toast），两者语义不同，不要混用。
 */
public class Result<T> {

    /** 业务成功码。 */
    public static final int SUCCESS_CODE = 0;

    private boolean success;
    private T data;
    private String errMsg;
    private int code;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private String userMsg;

    public Result() {
    }

    public Result(boolean success, T data, String errMsg, int code, String userMsg) {
        this.success = success;
        this.data = data;
        this.errMsg = errMsg;
        this.code = code;
        this.userMsg = userMsg;
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(true, data, null, SUCCESS_CODE, null);
    }

    public static <T> Result<T> ok(T data, String userMsg) {
        return new Result<>(true, data, null, SUCCESS_CODE, userMsg);
    }

    public static <T> Result<T> ok() {
        return new Result<>(true, null, null, SUCCESS_CODE, null);
    }

    /** 失败：userMsg 按错误码规则自动生成（4xxx 用户侧取错误信息，其余为 null）。 */
    public static <T> Result<T> error(int code, String errMsg) {
        return new Result<>(false, null, errMsg, code, ErrorCode.userMsgOf(code, errMsg));
    }

    public static <T> Result<T> error(int code, String errMsg, String userMsg) {
        return new Result<>(false, null, errMsg, code, userMsg);
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public String getErrMsg() {
        return errMsg;
    }

    public void setErrMsg(String errMsg) {
        this.errMsg = errMsg;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getUserMsg() {
        return userMsg;
    }

    public void setUserMsg(String userMsg) {
        this.userMsg = userMsg;
    }
}
