package com.manga.ai.user.dto;

import lombok.Data;

/**
 * 兑换结果VO
 */
@Data
public class RedeemResultVO {
    /** 本次获得的积分 */
    private Integer credits;

    /** 兑换后余额 */
    private Integer balanceAfter;
}
