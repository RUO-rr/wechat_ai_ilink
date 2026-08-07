package io.github.wangyangxu.ailink.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 管理 API 鉴权拦截器 —— 校验 Header {@code X-API-Token}，不匹配返回 401。
 * Token 从环境变量 {@code MANAGEMENT_API_TOKEN} 读取，未设置时使用默认值。
 */
@Component
public class ManagementApiInterceptor implements HandlerInterceptor {

    private static final String TOKEN_HEADER = "X-API-Token";

    @Value("${management.api.token}")
    private String token;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String provided = request.getHeader(TOKEN_HEADER);
        if (provided != null && MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\": \"unauthorized\"}");
        return false;
    }
}
