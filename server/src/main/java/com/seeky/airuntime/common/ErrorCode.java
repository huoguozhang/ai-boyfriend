package com.seeky.airuntime.common;

/**
 * 业务错误码，规则沿用 seeky-app-server 的 errCode.md：
 * 首位 4 表示用户侧错误、5 表示系统侧错误、6 为本服务（AI runtime）扩展段。
 * 常量与默认中文文案成对定义，业务代码只允许抛 {@code BusinessException(code, message)}。
 */
public final class ErrorCode {

    private ErrorCode() {
    }

    // ===== 4xxx 用户侧 =====
    public static final int PARAM_VALIDATION_FAILED = 4201;
    public static final String PARAM_VALIDATION_FAILED_MSG = "参数校验失败";

    public static final int PARAM_MISSING = 4202;
    public static final String PARAM_MISSING_MSG = "缺少必填参数";

    public static final int REQUEST_BODY_INVALID = 4204;
    public static final String REQUEST_BODY_INVALID_MSG = "请求体缺失或 JSON 格式错误";

    /** 请求的能力未配置（例如没配图像服务就要照片）。 */
    public static final int CAPABILITY_NOT_CONFIGURED = 4301;
    public static final String CAPABILITY_NOT_CONFIGURED_MSG = "该能力尚未配置";

    // ===== 44xx 鉴权 =====
    /** 没带 token 或 token 过期/非法。前端收到它一律清登录态、回登录页。 */
    public static final int UNAUTHORIZED = 4401;
    public static final String UNAUTHORIZED_MSG = "未登录或登录已过期";

    /** 登录了，但访问的不是自己的东西（例如拿别人的 sessionId 拉历史）。 */
    public static final int FORBIDDEN = 4403;
    public static final String FORBIDDEN_MSG = "无权访问该资源";

    /** 登录失败统一用这一句：不区分"用户不存在"和"密码错"，避免被撞库探出哪些用户名有效。 */
    public static final int LOGIN_FAILED = 4410;
    public static final String LOGIN_FAILED_MSG = "用户名或密码不正确";

    public static final int USERNAME_TAKEN = 4411;
    public static final String USERNAME_TAKEN_MSG = "用户名已被占用";

    public static final int ACCOUNT_DISABLED = 4412;
    public static final String ACCOUNT_DISABLED_MSG = "账号已被禁用";

    // ===== 5xxx 系统侧 =====
    public static final int SYSTEM_ERROR = 5100;
    public static final String SYSTEM_ERROR_MSG = "系统繁忙";

    /** 资源目录缺失（prompts / skills 目录被删或路径不对）。 */
    public static final int RESOURCE_NOT_FOUND = 5201;

    /** 资源内容非法（id 命名不合规、缺 frontmatter、模板缺变量）。 */
    public static final int RESOURCE_INVALID = 5202;

    /** 系统侧错误推给用户看的友好提示。 */
    public static final String FRIENDLY_ERROR_MSG = "系统开小差了，请稍后再试~";

    // ===== 6xxx AI 能力扩展段 =====
    public static final int AGENT_ERROR = 6000;
    public static final String AGENT_ERROR_MSG = "对话生成失败";

    public static final int MODEL_CONFIG_ERROR = 6001;
    public static final String MODEL_CONFIG_ERROR_MSG = "模型配置缺失或非法";

    /** 语音识别失败 / 未配置（前端拿它跳设置页的 asr 卡片）。 */
    public static final int ASR_ERROR = 6011;
    public static final String ASR_ERROR_MSG = "语音识别失败";

    public static final int SPEECH_ERROR = 6004;
    public static final String SPEECH_ERROR_MSG = "语音服务调用失败";

    public static final int MEDIA_ERROR = 6005;
    public static final String MEDIA_ERROR_MSG = "媒体生成失败";

    public static final int MEMORY_ERROR = 6006;
    public static final String MEMORY_ERROR_MSG = "记忆读写失败";

    /**
     * 失败时给用户的友好提示：4xxx 用户侧直接用 errMsg（本来就是给人看的），
     * 5xxx/6xxx 系统侧换成统一话术，避免把堆栈信息甩给用户。
     */
    public static String userMsgOf(int code, String errMsg) {
        if (code >= 4000 && code < 5000) {
            return errMsg;
        }
        if (code >= 5000) {
            return FRIENDLY_ERROR_MSG;
        }
        return null;
    }
}
