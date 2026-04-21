package com.manga.ai.scene.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 场景实体
 */
@Data
@TableName("scene")
public class Scene implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 系列ID
     */
    private Long seriesId;

    /**
     * 场景名称
     */
    private String sceneName;

    /**
     * 场景编码
     */
    private String sceneCode;

    /**
     * 场景描述
     */
    private String description;

    /**
     * 地点类型(室内/室外)
     */
    private String locationType;

    /**
     * 时间(白天/夜晚/黄昏)
     */
    private String timeOfDay;

    /**
     * 天气
     */
    private String weather;

    /**
     * 自定义提示词
     */
    private String customPrompt;

    /**
     * 风格关键词
     */
    private String styleKeywords;

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
