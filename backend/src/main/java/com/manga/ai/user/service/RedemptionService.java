package com.manga.ai.user.service;

import com.manga.ai.user.dto.RedeemResultVO;

/**
 * 兑换码服务接口
 */
public interface RedemptionService {

    /**
     * 兑换积分
     * @param userId 用户ID
     * @param code 兑换码
     * @return 兑换结果
     */
    RedeemResultVO redeem(Long userId, String code);
}
