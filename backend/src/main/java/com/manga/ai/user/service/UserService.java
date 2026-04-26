package com.manga.ai.user.service;

import com.manga.ai.user.dto.*;
import com.manga.ai.user.entity.User;

/**
 * 用户服务接口
 */
public interface UserService {

    /**
     * 发送验证码
     */
    void sendCode(SendCodeRequest request);

    /**
     * 注册
     */
    UserVO register(RegisterRequest request);

    /**
     * 登录
     */
    UserVO login(LoginRequest request);

    /**
     * 根据ID获取用户
     */
    User getById(Long id);

    /**
     * 根据邮箱获取用户
     */
    User getByEmail(String email);

    /**
     * 获取当前登录用户
     */
    User getCurrentUser();

    /**
     * 获取当前用户ID
     */
    Long getCurrentUserId();

    /**
     * 更新最后登录时间
     */
    void updateLastLogin(Long userId);

    /**
     * 扣除积分（原子操作，积分不足抛异常）
     * @param userId 用户ID
     * @param amount 扣除数量
     */
    void deductCredits(Long userId, int amount);

    /**
     * 扣除积分并记录
     * @param userId 用户ID
     * @param amount 扣除数量
     * @param usageType 用途类型
     * @param description 描述
     */
    void deductCredits(Long userId, int amount, String usageType, String description);

    /**
     * 扣除积分并记录(带关联信息)
     * @param userId 用户ID
     * @param amount 扣除数量
     * @param usageType 用途类型
     * @param description 描述
     * @param referenceId 关联ID
     * @param referenceType 关联类型
     */
    void deductCredits(Long userId, int amount, String usageType, String description, Long referenceId, String referenceType);

    /**
     * 返还积分
     * @param userId 用户ID
     * @param amount 返还数量
     */
    void refundCredits(Long userId, int amount);

    /**
     * 返还积分并记录
     * @param userId 用户ID
     * @param amount 返还数量
     * @param description 描述
     */
    void refundCredits(Long userId, int amount, String description);

    /**
     * 返还积分并记录(带关联信息)
     * @param userId 用户ID
     * @param amount 返还数量
     * @param description 描述
     * @param referenceId 关联ID
     * @param referenceType 关联类型
     */
    void refundCredits(Long userId, int amount, String description, Long referenceId, String referenceType);

    /**
     * 获取用户积分
     * @param userId 用户ID
     * @return 积分余额
     */
    Integer getUserCredits(Long userId);

    /**
     * 检查积分是否足够
     * @param userId 用户ID
     * @param amount 所需积分
     * @return 是否足够
     */
    boolean hasSufficientCredits(Long userId, int amount);
}
