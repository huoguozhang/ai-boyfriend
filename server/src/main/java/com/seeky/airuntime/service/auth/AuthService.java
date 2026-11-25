package com.seeky.airuntime.service.auth;

import com.seeky.airuntime.common.BusinessException;
import com.seeky.airuntime.common.ErrorCode;
import com.seeky.airuntime.dto.AuthDtos.AuthResult;
import com.seeky.airuntime.dto.AuthDtos.AuthUser;
import com.seeky.airuntime.dto.AuthDtos.LoginRequest;
import com.seeky.airuntime.dto.AuthDtos.RegisterRequest;
import com.seeky.airuntime.store.UserStore;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 用户与登录态的业务层：注册、登录、按 id 取用户。
 *
 * <p>几条刻意的取舍：
 * <ul>
 *   <li><b>登录失败不说原因</b>：用户不存在和密码错都回 {@code 4410 用户名或密码不正确}。
 *       分开说等于送人一个"用户名枚举"接口。</li>
 *   <li><b>不存在的用户也要算一次哈希</b>：否则"查无此人"比"密码错"快一个数量级，
 *       用响应时间就能把有效用户名试出来。</li>
 *   <li><b>账号禁用放在校验密码之后</b>：先确认是他本人，再告诉他账号被禁了。</li>
 *   <li><b>用户名不区分大小写</b>：唯一性和查询都走 {@code lower()}，与数据库索引一致。</li>
 * </ul>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 3~24 位：允许中文、字母、数字、下划线、点、横线（演示 Demo 不搞复杂规则）。 */
    private static final Pattern USERNAME = Pattern.compile("^[\\p{L}\\p{N}_.-]{3,24}$");
    private static final int PASSWORD_MIN = 6;
    private static final int PASSWORD_MAX = 64;
    private static final int NICKNAME_MAX = 24;

    private final UserStore users;
    private final PasswordHasher hasher;
    private final JwtService jwt;

    /** 用户不存在时拿它空跑一次哈希，让两条失败路径耗时一致。 */
    private final String timingDummyHash;

    public AuthService(UserStore users, PasswordHasher hasher, JwtService jwt) {
        this.users = users;
        this.hasher = hasher;
        this.jwt = jwt;
        this.timingDummyHash = hasher.hash("timing-equalizer");
    }

    // ------------------------------------------------------------------ 注册

    public AuthResult register(RegisterRequest request) {
        String username = requireUsername(request == null ? null : request.username());
        String password = requirePassword(request == null ? null : request.password());
        String nickname = nicknameOr(request.nickname(), username);

        if (users.existsByUsername(username)) {
            throw new BusinessException(ErrorCode.USERNAME_TAKEN, ErrorCode.USERNAME_TAKEN_MSG);
        }

        String id = "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        long now = System.currentTimeMillis();
        try {
            users.insert(id, username, hasher.hash(password), nickname, now);
        } catch (BusinessException ex) {
            // 并发注册：两边都过了 exists 检查，靠唯一索引拦下后到的那条
            if (users.existsByUsername(username)) {
                throw new BusinessException(ErrorCode.USERNAME_TAKEN, ErrorCode.USERNAME_TAKEN_MSG);
            }
            throw ex;
        }
        log.info("[auth] 注册成功 id={} username={}", id, username);
        return issue(new UserStore.UserRow(id, username, nickname, "", true, now, 0L));
    }

    // ------------------------------------------------------------------ 登录

    public AuthResult login(LoginRequest request) {
        String username = request == null || request.username() == null ? "" : request.username().trim();
        String password = request.password() == null ? "" : request.password();
        if (username.isEmpty() || password.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "请输入用户名和密码");
        }

        Optional<UserStore.UserRow> found = users.findByUsername(username);
        if (found.isEmpty()) {
            hasher.matches(password, timingDummyHash);
            throw new BusinessException(ErrorCode.LOGIN_FAILED, ErrorCode.LOGIN_FAILED_MSG);
        }
        UserStore.UserRow user = found.get();
        if (!hasher.matches(password, user.passwordHash())) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED, ErrorCode.LOGIN_FAILED_MSG);
        }
        if (!user.enabled()) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED, ErrorCode.ACCOUNT_DISABLED_MSG);
        }

        long now = System.currentTimeMillis();
        users.touchLogin(user.id(), now);
        log.info("[auth] 登录成功 id={} username={}", user.id(), user.username());
        return issue(new UserStore.UserRow(user.id(), user.username(), user.nickname(),
                user.passwordHash(), true, user.createdAt(), now));
    }

    // ------------------------------------------------------------------ 查询

    public AuthUser me(String userId) {
        return users.findById(userId)
                .map(AuthService::toView)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "用户不存在或已被删除"));
    }

    // ------------------------------------------------------------------ 内部

    private AuthResult issue(UserStore.UserRow user) {
        JwtService.Issued issued = jwt.issue(user.id(), user.username());
        return new AuthResult(issued.token(), "Bearer", issued.expiresIn(), toView(user));
    }

    private static AuthUser toView(UserStore.UserRow user) {
        return new AuthUser(
                user.id(),
                user.username(),
                user.nickname(),
                iso(user.createdAt()),
                user.lastLoginAt() > 0 ? iso(user.lastLoginAt()) : null);
    }

    private static String iso(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).toString();
    }

    private static String requireUsername(String raw) {
        String username = raw == null ? "" : raw.trim();
        if (username.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "用户名不能为空");
        }
        if (!USERNAME.matcher(username).matches()) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_FAILED,
                    "用户名需 3~24 位，可用中文、字母、数字、下划线、点、横线");
        }
        return username;
    }

    private static String requirePassword(String raw) {
        String password = raw == null ? "" : raw;
        if (password.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "密码不能为空");
        }
        if (password.length() < PASSWORD_MIN || password.length() > PASSWORD_MAX) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_FAILED,
                    "密码长度需 " + PASSWORD_MIN + "~" + PASSWORD_MAX + " 位");
        }
        return password;
    }

    private static String nicknameOr(String raw, String fallback) {
        String nickname = raw == null ? "" : raw.trim();
        if (nickname.isEmpty()) {
            return fallback;
        }
        return nickname.length() <= NICKNAME_MAX ? nickname : nickname.substring(0, NICKNAME_MAX);
    }
}
