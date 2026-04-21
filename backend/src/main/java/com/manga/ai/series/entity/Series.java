package com.manga.ai.series.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 系列实体
 */
@Data
@TableName("series")
public class Series implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 系列名称
     */
    private String seriesName;

    /**
     * 剧本大纲
     */
    private String outline;

    /**
     * 背景设定/世界观
     */
    private String background;

    /**
     * 人物介绍原文
     */
    private String characterIntro;

    /**
     * 风格关键词
     */
    private String styleKeywords;

    /**
     * 色调偏好
     */
    private String colorPreference;

    /**
     * 美术风格参考
     */
    private String artStyleRef;

    /**
     * 状态: 0-初始化中 1-待审核 2-已锁定
     */
    private Integer status;

    /**
     * 项目目录路径
     */
    private String projectPath;

    /**
     * 全局风格Seed
     */
    private Long globalSeed;

    /**
     * 全局风格Prompt
     */
    private String globalStylePrompt;

    /**
     * 图片比例: 16:9 / 3:4
     */
    private String aspectRatio;

    /**
     * 清晰度: standard / hd / ultra
     */
    private String quality;

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
     * 创建人
     */
    private String createdBy;

    /**
     * 是否删除
     */
    @TableLogic
    private Integer isDeleted;

    /**
     * 删除时间（用于回收站过期判断）
     */
    private LocalDateTime deletedAt;
}
