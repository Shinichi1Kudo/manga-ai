package com.manga.ai.shot.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 分镜-道具关联实体
 */
@Data
@TableName("shot_prop")
public class ShotProp implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 分镜ID
     */
    private Long shotId;

    /**
     * 道具ID
     */
    private Long propId;

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
     * 旋转角度
     */
    private BigDecimal rotation;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
