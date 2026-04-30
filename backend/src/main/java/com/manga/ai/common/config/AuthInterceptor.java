package com.manga.ai.common.config;

import com.manga.ai.user.service.TokenService;
import com.manga.ai.user.service.impl.UserServiceImpl.UserContextHolder;
import com.manga.ai.user.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证拦截器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final TokenService tokenService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // OPTIONS 请求直接放行
        if ("OPTIONS".equals(request.getMethod())) {
            return true;
        }

        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
            if (jwtUtil.validateToken(token)) {
                Long userId = jwtUtil.getUserId(token);

                // JWT签名验证通过后，再检查Redis中是否存在该token
                try {
                    if (tokenService.validateTokenInRedis(token)) {
                        UserContextHolder.setUserId(userId);
                        log.debug("用户认证成功: userId={}", userId);
                    } else {
                        // Redis中不存在token，可能是Redis重启或token过期
                        // 降级为纯JWT验证（JWT有效期内仍然信任）
                        log.warn("Token在Redis中不存在，降级为纯JWT验证: userId={}", userId);
                        UserContextHolder.setUserId(userId);
                    }
                } catch (Exception e) {
                    // Redis不可用时降级为纯JWT验证
                    log.error("Redis连接异常，降级为纯JWT验证: userId={}", userId, e);
                    UserContextHolder.setUserId(userId);
                }
            }
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserContextHolder.clear();
    }
}