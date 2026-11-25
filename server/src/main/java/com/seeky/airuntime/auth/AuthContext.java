package com.seeky.airuntime.auth;

import com.seeky.airuntime.common.BusinessException;
import com.seeky.airuntime.common.ErrorCode;
import org.springframework.web.server.ServerWebExchange;

/**
 * 当前请求是谁：{@link JwtAuthFilter} 校验完 token 后把身份塞进 exchange 属性，这里负责读。
 *
 * <p>用 exchange 属性而不是 ThreadLocal：WebFlux 一条请求会在多个线程上跑（事件循环 + 调度线程），
 * ThreadLocal 会丢；Reactor Context 则要一路 {@code contextWrite} 传下去，控制器签名会变脏。
 * exchange 属性天然是"这条请求"的作用域，够用且不侵入业务代码。
 */
public final class AuthContext {

    static final String USER_ID_ATTR = "auth.userId";
    static final String USERNAME_ATTR = "auth.username";

    private AuthContext() {
    }

    /** 当前用户 id；未经 {@link JwtAuthFilter} 的路径（登录/注册/健康检查）会是 null。 */
    public static String userId(ServerWebExchange exchange) {
        return (String) exchange.getAttributes().get(USER_ID_ATTR);
    }

    public static String username(ServerWebExchange exchange) {
        return (String) exchange.getAttributes().get(USERNAME_ATTR);
    }

    /**
     * 取当前用户 id，没有就抛 4401。
     *
     * <p>业务接口本是过滤器兜住的，正常走不到抛异常这条线；留着是为了"过滤器白名单被改错"
     * 时不会静默降级成匿名用户 —— 宁可报错，也别把 A 的数据给 B 看。
     */
    public static String requireUserId(ServerWebExchange exchange) {
        String userId = userId(exchange);
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED_MSG);
        }
        return userId;
    }
}
