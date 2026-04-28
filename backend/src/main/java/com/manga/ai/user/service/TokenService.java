package com.manga.ai.user.service;

public interface TokenService {

    /**
     * 存储token到Redis
     * @param token JWT token
     * @param userId 用户ID
     */
    void storeToken(String token, Long userId);

    /**
     * 验证token在Redis中是否存在（即是否有效）
     * @param token JWT token
     * @return token是否有效
     */
    boolean validateTokenInRedis(String token);

    /**
     * 从token获取userId
     * @param token JWT token
     * @return userId，如果token不存在返回null
     */
    Long getUserIdByToken(String token);

    /**
     * 删除token（退出登录时调用）
     * @param token JWT token
     */
    void removeToken(String token);

    /**
     * 删除某用户所有token（强制下线时调用）
     * @param userId 用户ID
     */
    void removeAllUserTokens(Long userId);
}