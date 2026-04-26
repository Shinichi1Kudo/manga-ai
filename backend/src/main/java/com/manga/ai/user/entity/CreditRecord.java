package com.manga.ai.user.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 积分记录实体
 */
@Data
@TableName("credit_record")
public class CreditRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 积分数量(扣费为负,返还/奖励为正)
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
     * 用途: VIDEO_GENERATION/IMAGE_GENERATION/SCRIPT_PARSE等
     */
    private String usageType;

    /**
     * 描述: 如"视频生成-分镜1"
     */
    private String description;

    /**
     * 关联ID(shotId/roleId等)
     */
    private Long referenceId;

    /**
     * 关联类型: SHOT/ROLE/SCENE/PROP
     */
    private String referenceType;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
