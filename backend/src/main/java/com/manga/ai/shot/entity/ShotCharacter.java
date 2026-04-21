package com.manga.ai.shot.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 分镜-角色关联实体
 */
@Data
@TableName("shot_character")
public class ShotCharacter implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 分镜ID
     */
    private Long shotId;

    /**
     * 角色ID
     */
    private Long roleId;

    /**
     * 角色动作描述
     */
    private String characterAction;

    /**
     * 角色表情
     */
    private String characterExpression;

    /**
     * 服装编号
     */
    private Integer clothingId;

    /**
     * X位置(百分比)
     */
    private BigDecimal positionX;

    /**
     * Y位置(百分比)
     */
    private BigDecimal positionY;

    /**
     * 缩放比例
     */
    private BigDecimal scale;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
