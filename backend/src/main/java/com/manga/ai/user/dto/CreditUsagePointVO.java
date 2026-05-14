package com.manga.ai.user.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 积分使用趋势点
 */
@Data
public class CreditUsagePointVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String label;
    private Integer deducted;
    private Integer rewarded;
    private Integer refunded;
}
