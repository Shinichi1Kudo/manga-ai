package com.manga.ai.user.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 兑换码实体
 */
@Data
@TableName("redemption_code")
public class RedemptionCode implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 兑换码（区分大小写） */
    private String code;

    /** 积分数量 */
    private Integer credits;

    /** 状态：0-未使用，1-已使用 */
    private Integer status;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 使用时间 */
    private LocalDateTime usedAt;

    /** 使用者用户ID */
    private Long usedBy;

    /** 备注 */
    private String remark;
}
