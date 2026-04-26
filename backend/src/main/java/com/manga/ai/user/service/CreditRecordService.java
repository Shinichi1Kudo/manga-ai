package com.manga.ai.user.service;

import com.manga.ai.common.result.PageResult;
import com.manga.ai.user.dto.CreditRecordVO;

import java.util.List;

/**
 * 积分记录服务接口
 */
public interface CreditRecordService {

    /**
     * 记录积分扣除
     * @param userId 用户ID
     * @param amount 积分数量(正数)
     * @param usageType 用途类型
     * @param description 描述
     */
    void recordDeduction(Long userId, int amount, String usageType, String description);

    /**
     * 记录积分扣除(带关联信息)
     * @param userId 用户ID
     * @param amount 积分数量(正数)
     * @param usageType 用途类型
     * @param description 描述
     * @param referenceId 关联ID
     * @param referenceType 关联类型
     */
    void recordDeduction(Long userId, int amount, String usageType, String description, Long referenceId, String referenceType);

    /**
     * 记录积分返还
     * @param userId 用户ID
     * @param amount 积分数量(正数)
     * @param description 描述
     */
    void recordRefund(Long userId, int amount, String description);

    /**
     * 记录积分返还(带关联信息)
     * @param userId 用户ID
     * @param amount 积分数量(正数)
     * @param description 描述
     * @param referenceId 关联ID
     * @param referenceType 关联类型
     */
    void recordRefund(Long userId, int amount, String description, Long referenceId, String referenceType);

    /**
     * 获取积分记录列表(分页)
     * @param userId 用户ID
     * @param page 页码
     * @param pageSize 每页数量
     * @param type 类型筛选(可选)
     * @return 分页结果
     */
    PageResult<CreditRecordVO> getCreditRecords(Long userId, Integer page, Integer pageSize, String type);

    /**
     * 获取最近的积分记录
     * @param userId 用户ID
     * @param limit 数量限制
     * @return 记录列表
     */
    List<CreditRecordVO> getRecentRecords(Long userId, Integer limit);
}
