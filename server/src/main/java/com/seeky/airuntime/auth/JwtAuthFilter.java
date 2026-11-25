package com.seeky.airuntime.auth;

import com.seeky.airuntime.common.ErrorCode;
import com.seeky.airuntime.common.Result;
import com.seeky.airuntime.service.auth.JwtService;
import io.jsonwebtoken.Claims;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * JWT 鉴权过滤器：{@code /api/v1/**} 全量强制，只有登录/注册放行。
 *
 * <p>校验通过后把 {@code userId / username} 写进 exchange 属性，业务侧用
 * {@link AuthContext#requireUserId} 取 —— 控制器里<b>不再信任请求体里的 userId</b>。
 *
 * <p><b>失败返回 HTTP 401</b>（而不是项目惯例的 HTTP 200 + 业务码）：鉴权失败是传输层语义，
 * 让浏览器、curl、代理都能一眼看出"没登录"；body 仍然是统一的 {@link Result} 信封，
 * 前端照旧按 {@code code=4401} 处理。这是本项目唯一一处偏离"HTTP 恒 200"的地方。
 *
 * <p>只拦 {@code /api/v1/**}：健康检查、OpenAPI 文档、将来托管的前端静态资源都不该要 token。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class JwtAuthFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private static final String GUARDED_PREFIX = "/api/v1/";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final MediaType JSON_UTF8 = MediaType.parseMediaType("application/json;charset=UTF-8");

    /** 无需 token 的路径（精确匹配，不含前缀匹配，避免被 /auth/login/xxx 绕过）。 */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/v1/auth/register",
            "/api/v1/auth/login");

    private final JwtService jwt;
    private final ObjectMapper mapper;

    public JwtAuthFilter(JwtService jwt, ObjectMapper mapper) {
        this.jwt = jwt;
        this.mapper = mapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        // 预检请求不带 Authorization，直接放行，否则浏览器的 CORS 预检会被 401 打死
        if (HttpMethod.OPTIONS.equals(request.getMethod())) {
            return chain.filter(exchange);
        }

        String path = normalize(request.getPath().pathWithinApplication().value());
        if (!path.startsWith(GUARDED_PREFIX) || PUBLIC_PATHS.contains(path)) {
            return chain.filter(exchange);
        }

        String token = bearer(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        Optional<Claims> claims = jwt.verify(token);
        if (claims.isEmpty()) {
            log.debug("[auth] 拒绝 {} {}：token 缺失或无效", request.getMethod(), path);
            return reject(exchange, ErrorCode.UNAUTHORIZED_MSG);
        }

        exchange.getAttributes().put(AuthContext.USER_ID_ATTR, claims.get().getSubject());
        exchange.getAttributes().put(AuthContext.USERNAME_ATTR,
                claims.get().get("username", String.class));
        return chain.filter(exchange);
    }

    private static String normalize(String path) {
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    private static String bearer(String header) {
        if (header == null) {
            return null;
        }
        String value = header.trim();
        if (value.length() <= BEARER_PREFIX.length()
                || !value.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        return value.substring(BEARER_PREFIX.length()).trim();
    }

    private Mono<Void> reject(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(JSON_UTF8);
        // 别让浏览器缓存这条 401，否则拿到新 token 也可能读到旧结果
        response.getHeaders().setCacheControl("no-store");
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(Result.error(ErrorCode.UNAUTHORIZED, message));
        } catch (RuntimeException ex) {
            // 序列化失败也得让请求拿到 401，不能因为"错误响应本身写不出去"变成 500
            body = ("{\"success\":false,\"code\":" + ErrorCode.UNAUTHORIZED + "}")
                    .getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }
}
