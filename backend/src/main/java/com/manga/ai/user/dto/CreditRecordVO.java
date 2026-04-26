package com.manga.ai.user.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 积分记录VO
 */
@Data
public class CreditRecordVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /**
     * 积分数量
     */
    private Integer amount;

    /**
     * 交易后余额
     */
    private Integer balanceAfter;

    /**
     * 类型: DEDUCT/REFUND/REWARD
     */
    private String type;

    /**
     * 类型描述
     */
    private String typeDesc;

    /**
     * 用途类型
     */
    private String usageType;

    /**
     * 用途描述
     */
    private String usageTypeDesc;

    /**
     * 描述
     */
    private String description;

    /**
     * 格式化时间: MM-DD HH:mm
     */
    private String createdAt;
}
