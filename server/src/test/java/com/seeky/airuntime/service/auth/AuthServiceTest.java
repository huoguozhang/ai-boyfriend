package com.seeky.airuntime.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.seeky.airuntime.common.BusinessException;
import com.seeky.airuntime.common.ErrorCode;
import com.seeky.airuntime.config.AuthProperties;
import com.seeky.airuntime.dto.AuthDtos.AuthResult;
import com.seeky.airuntime.dto.AuthDtos.LoginRequest;
import com.seeky.airuntime.dto.AuthDtos.RegisterRequest;
import com.seeky.airuntime.store.UserStore;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 注册 / 登录的业务规则。
 *
 * <p>UserStore 用 mock：这里要验的是"规则对不对"（唯一性、错误码、哈希形态、禁用账号），
 * 而不是 SQL 拼得对不对 —— 那是 {@code ChatStoreTest} 那条真库链路的事。
 */
class AuthServiceTest {

    private UserStore users;
    private PasswordHasher hasher;
    private JwtService jwt;
    private AuthService auth;

    @BeforeEach
    void setUp() {
        users = mock(UserStore.class);
        AuthProperties props = new AuthProperties(
                "0123456789abcdef0123456789abcdef", 3600, "ai-soulmate", 1000);
        hasher = new PasswordHasher(props);
        jwt = new JwtService(props);
        auth = new AuthService(users, hasher, jwt);
    }

    // ------------------------------------------------------------------ 注册

    @Test
    @DisplayName("注册成功：发给前端 token，落库的是哈希不是明文")
    void registerIssuesTokenAndStoresHash() {
        when(users.existsByUsername("chenyu")).thenReturn(false);

        AuthResult result = auth.register(new RegisterRequest("chenyu", "chenyu123", "陈屿"));

        assertThat(result.token()).isNotBlank();
        assertThat(result.tokenType()).isEqualTo("Bearer");
        assertThat(result.user().username()).isEqualTo("chenyu");
        assertThat(result.user().nickname()).isEqualTo("陈屿");

        // token 立刻能用，且 sub 就是库里那条用户的 id
        assertThat(jwt.verify(result.token())).isPresent();
        assertThat(jwt.verify(result.token()).get().getSubject()).isEqualTo(result.user().id());

        ArgumentCaptor<String> storedHash = ArgumentCaptor.forClass(String.class);
        verify(users).insert(anyString(), anyString(), storedHash.capture(), anyString(), anyLong());
        assertThat(storedHash.getValue()).startsWith("pbkdf2-sha256$");
        assertThat(storedHash.getValue()).doesNotContain("chenyu123");
        assertThat(hasher.matches("chenyu123", storedHash.getValue())).isTrue();
    }

    @Test
    @DisplayName("昵称留空就用用户名顶上，不会变成空字符串")
    void registerFallsBackToUsernameAsNickname() {
        when(users.existsByUsername("nobody")).thenReturn(false);

        AuthResult result = auth.register(new RegisterRequest("nobody", "123456", "   "));

        assertThat(result.user().nickname()).isEqualTo("nobody");
    }

    @Test
    @DisplayName("重名直接拒绝，不落库")
    void registerRejectsDuplicate() {
        when(users.existsByUsername("chenyu")).thenReturn(true);

        assertThatThrownBy(() -> auth.register(new RegisterRequest("chenyu", "chenyu123", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.USERNAME_TAKEN);

        verify(users, never()).insert(anyString(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("并发注册撞唯一索引：existsByUsername 二次复查后仍报重名，而不是把 SQL 异常漏出去")
    void registerRecoversFromConcurrentInsert() {
        // 第一次说没有，第二次（insert 抛错后复查）说有了 —— 模拟另一个请求刚刚插进去
        when(users.existsByUsername("racer")).thenReturn(false, true);
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.PARAM_VALIDATION_FAILED, "duplicate key"))
                .when(users).insert(anyString(), anyString(), anyString(), anyString(), anyLong());

        assertThatThrownBy(() -> auth.register(new RegisterRequest("racer", "racer123", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.USERNAME_TAKEN);
    }

    @Test
    @DisplayName("用户名/密码不合规在参数层就挡住，不去碰数据库")
    void registerValidatesInput() {
        assertThatThrownBy(() -> auth.register(new RegisterRequest("ab", "123456", null)))
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_VALIDATION_FAILED);

        assertThatThrownBy(() -> auth.register(new RegisterRequest("ok_name", "12345", null)))
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_VALIDATION_FAILED);

        assertThatThrownBy(() -> auth.register(new RegisterRequest("  ", "123456", null)))
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_MISSING);

        verify(users, never()).insert(anyString(), anyString(), anyString(), anyString(), anyLong());
    }

    // ------------------------------------------------------------------ 登录

    @Test
    @DisplayName("登录成功：刷新 last_login_at，返回的 lastLoginAt 不是 null")
    void loginSucceeds() {
        String hash = hasher.hash("chenyu123");
        when(users.findByUsername("chenyu")).thenReturn(Optional.of(
                new UserStore.UserRow("u_1", "chenyu", "陈屿", hash, true, 1_700_000_000_000L, 0L)));

        AuthResult result = auth.login(new LoginRequest("chenyu", "chenyu123"));

        assertThat(result.user().id()).isEqualTo("u_1");
        assertThat(result.user().lastLoginAt()).isNotNull();
        assertThat(jwt.verify(result.token()).get().getSubject()).isEqualTo("u_1");
        verify(users).touchLogin(anyString(), anyLong());
    }

    @Test
    @DisplayName("用户不存在与密码错返回同一个错误码（不给用户名枚举留接口）")
    void loginHidesWhichPartFailed() {
        when(users.findByUsername("ghost")).thenReturn(Optional.empty());
        when(users.findByUsername("chenyu")).thenReturn(Optional.of(new UserStore.UserRow(
                "u_1", "chenyu", "陈屿", hasher.hash("chenyu123"), true, 1L, 0L)));

        assertThatThrownBy(() -> auth.login(new LoginRequest("ghost", "whatever")))
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.LOGIN_FAILED);

        assertThatThrownBy(() -> auth.login(new LoginRequest("chenyu", "wrong-pass")))
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.LOGIN_FAILED);

        // 用户名错的那条路径也要真跑一次哈希（时间侧信道），所以绝不能提前 return
        assertThat(ErrorCode.LOGIN_FAILED_MSG).isEqualTo("用户名或密码不正确");
    }

    @Test
    @DisplayName("账号禁用：密码对了才告诉他是禁用，不是密码错")
    void loginRejectsDisabledAccount() {
        when(users.findByUsername("banned")).thenReturn(Optional.of(new UserStore.UserRow(
                "u_2", "banned", "已封", hasher.hash("123456"), false, 1L, 0L)));

        assertThatThrownBy(() -> auth.login(new LoginRequest("banned", "123456")))
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.ACCOUNT_DISABLED);
    }

    @Test
    @DisplayName("空用户名/空密码按缺参处理，不查库")
    void loginValidatesInput() {
        assertThatThrownBy(() -> auth.login(new LoginRequest("", "123456")))
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_MISSING);

        assertThatThrownBy(() -> auth.login(new LoginRequest("chenyu", null)))
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_MISSING);

        verify(users, never()).findByUsername(anyString());
    }

    // ------------------------------------------------------------------ me

    @Test
    @DisplayName("me：用户不存在按未登录处理")
    void meRejectsUnknownUser() {
        when(users.findById("u_ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> auth.me("u_ghost"))
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("me：对外结构里没有 passwordHash 字段")
    void meNeverLeaksHash() {
        when(users.findById("u_1")).thenReturn(Optional.of(new UserStore.UserRow(
                "u_1", "chenyu", "陈屿", hasher.hash("chenyu123"), true, 1_700_000_000_000L, 0L)));

        var view = auth.me("u_1");

        assertThat(view.username()).isEqualTo("chenyu");
        // AuthUser 是 record，字段就这五个；任何"顺手把 UserRow 返回出去"都会在这里露馅
        assertThat(view.getClass().getRecordComponents()).hasSize(5);
        assertThat(java.util.Arrays.stream(view.getClass().getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("passwordHash", "password");
    }
}
