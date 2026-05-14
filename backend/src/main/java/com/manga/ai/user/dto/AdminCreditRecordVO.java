package com.manga.ai.user.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 管理员积分流水记录
 */
@Data
public class AdminCreditRecordVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long userId;
    private String email;
    private String nickname;
    private Integer amount;
    private Integer balanceAfter;
    private String type;
    private String typeDesc;
    private String usageType;
    private String usageTypeDesc;
    private String description;
    private String createdAt;
}
