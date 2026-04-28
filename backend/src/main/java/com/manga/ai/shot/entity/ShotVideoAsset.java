package com.manga.ai.shot.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 分镜视频资产版本实体
 */
@Data
@TableName("shot_video_asset")
public class ShotVideoAsset implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 分镜ID
     */
    private Long shotId;

    /**
     * 版本号
     */
    private Integer version;

    /**
     * 视频URL
     */
    private String videoUrl;

    /**
     * 缩略图URL
     */
    private String thumbnailUrl;

    /**
     * 是否为当前激活版本(0-否,1-是)
     */
    private Integer isActive;

    /**
     * 生成耗时（秒）
     */
    private Integer generationDuration;

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
}
