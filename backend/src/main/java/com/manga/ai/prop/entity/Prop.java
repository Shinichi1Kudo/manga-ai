package com.manga.ai.prop.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 道具实体
 */
@Data
@TableName("prop")
public class Prop implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 系列ID
     */
    private Long seriesId;

    /**
     * 道具名称
     */
    private String propName;

    /**
     * 道具编码
     */
    private String propCode;

    /**
     * 道具描述
     */
    private String description;

    /**
     * 道具类型
     */
    private String propType;

    /**
     * 颜色
     */
    private String color;

    /**
     * 尺寸
     */
    private String size;

    /**
     * 自定义提示词
     */
    private String customPrompt;

    /**
     * 风格关键词
     */
    private String styleKeywords;

    /**
     * 图片比例
     */
    private String aspectRatio;

    /**
     * 清晰度
     */
    private String quality;

    /**
     * 状态: 0-生成中 1-待审核 2-已确认 3-已锁定
     */
    private Integer status;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 是否删除
     */
    @TableLogic
    private Integer isDeleted;
}
