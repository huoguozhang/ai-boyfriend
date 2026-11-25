package com.seeky.airuntime.controller;

import com.seeky.airuntime.auth.AuthContext;
import com.seeky.airuntime.common.Result;
import com.seeky.airuntime.dto.AuthDtos.AuthResult;
import com.seeky.airuntime.dto.AuthDtos.AuthUser;
import com.seeky.airuntime.dto.AuthDtos.LoginRequest;
import com.seeky.airuntime.dto.AuthDtos.RegisterRequest;
import com.seeky.airuntime.service.auth.AuthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

/**
 * 账号接口：注册、登录、查自己。
 *
 * <p>注册成功<b>直接返回 token</b>（省一次登录往返），前端拿到就能进主界面。
 *
 * <p>没有 {@code /logout}：JWT 是无状态的，服务端没有会话可销毁，退出登录就是前端删掉 token。
 * 做一个"永远返回成功"的空接口只会让人误以为服务端真的吊销了 —— 要真正吊销就得引黑名单/版本号，
 * 那是另一个量级的改动。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    public Result<AuthResult> register(@RequestBody(required = false) RegisterRequest request) {
        return Result.ok(auth.register(request));
    }

    @PostMapping("/login")
    public Result<AuthResult> login(@RequestBody(required = false) LoginRequest request) {
        return Result.ok(auth.login(request));
    }

    /** 当前 token 对应的用户（前端刷新页面时用它校验登录态是否还有效）。 */
    @PostMapping("/me")
    public Result<AuthUser> me(ServerWebExchange exchange) {
        return Result.ok(auth.me(AuthContext.requireUserId(exchange)));
    }

    @GetMapping("/me")
    public Result<AuthUser> meGet(ServerWebExchange exchange) {
        return me(exchange);
    }
}
