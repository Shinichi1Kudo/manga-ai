package com.manga.ai.user.service.impl;

import com.manga.ai.user.service.TokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class TokenServiceImpl implements TokenService {

    private final StringRedisTemplate redisTemplate;

    @Value("${jwt.expiration:2592000000}")
    private Long jwtExpiration;

    private static final String TOKEN_KEY_PREFIX = "auth:token:";
    private static final String USER_TOKENS_KEY_PREFIX = "auth:user:tokens:";

    public TokenServiceImpl(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void storeToken(String token, Long userId) {
        try {
            String tokenKey = TOKEN_KEY_PREFIX + token;
            String userTokensKey = USER_TOKENS_KEY_PREFIX + userId;

            redisTemplate.opsForValue().set(tokenKey, String.valueOf(userId),
                    jwtExpiration, TimeUnit.MILLISECONDS);
            redisTemplate.opsForSet().add(userTokensKey, token);
            redisTemplate.expire(userTokensKey, jwtExpiration + 86400000L, TimeUnit.MILLISECONDS);

            log.debug("Token stored in Redis: userId={}", userId);
        } catch (Exception e) {
            log.error("Redis storeToken failed, userId={}", userId, e);
        }
    }

    @Override
    public boolean validateTokenInRedis(String token) {
        try {
            String tokenKey = TOKEN_KEY_PREFIX + token;
            return Boolean.TRUE.equals(redisTemplate.hasKey(tokenKey));
        } catch (Exception e) {
            log.error("Redis validateToken failed, falling back to JWT-only validation", e);
            // Redis不可用时降级：返回true让JWT验证通过
            return true;
        }
    }

    @Override
    public Long getUserIdByToken(String token) {
        try {
            String tokenKey = TOKEN_KEY_PREFIX + token;
            String userIdStr = redisTemplate.opsForValue().get(tokenKey);
            if (userIdStr != null) {
                return Long.parseLong(userIdStr);
            }
            return null;
        } catch (Exception e) {
            log.error("Redis getUserIdByToken failed", e);
            return null;
        }
    }

    @Override
    public void removeToken(String token) {
        try {
            String tokenKey = TOKEN_KEY_PREFIX + token;
            String userIdStr = redisTemplate.opsForValue().get(tokenKey);
            redisTemplate.delete(tokenKey);

            if (userIdStr != null) {
                String userTokensKey = USER_TOKENS_KEY_PREFIX + userIdStr;
                redisTemplate.opsForSet().remove(userTokensKey, token);
            }

            log.debug("Token removed from Redis");
        } catch (Exception e) {
            log.error("Redis removeToken failed", e);
        }
    }

    @Override
    public void removeAllUserTokens(Long userId) {
        try {
            String userTokensKey = USER_TOKENS_KEY_PREFIX + userId;
            var tokens = redisTemplate.opsForSet().members(userTokensKey);
            if (tokens != null) {
                for (String token : tokens) {
                    redisTemplate.delete(TOKEN_KEY_PREFIX + token);
                }
            }
            redisTemplate.delete(userTokensKey);
            log.info("All tokens removed for userId={}", userId);
        } catch (Exception e) {
            log.error("Redis removeAllUserTokens failed, userId={}", userId, e);
        }
    }
}