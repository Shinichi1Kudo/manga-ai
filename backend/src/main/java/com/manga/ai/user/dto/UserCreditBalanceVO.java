package com.manga.ai.user.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 用户积分余额概览
 */
@Data
public class UserCreditBalanceVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private String email;
    private String nickname;
    private Integer credits;
    private Integer totalDeducted;
    private Integer totalRewarded;
    private Integer totalRefunded;
    private String lastUsedAt;
}
