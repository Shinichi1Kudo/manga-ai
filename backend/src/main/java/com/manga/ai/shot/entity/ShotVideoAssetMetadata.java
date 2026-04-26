package com.manga.ai.shot.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 分镜视频资产元数据实体
 */
@Data
@TableName("shot_video_asset_metadata")
public class ShotVideoAssetMetadata implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 视频资产ID
     */
    private Long shotVideoAssetId;

    /**
     * 生成模型
     */
    private String model;

    /**
     * 生成提示词
     */
    private String prompt;

    /**
     * 参考图URLs(JSON数组)
     */
    private String referenceUrls;

    /**
     * 其他生成参数(JSON)
     */
    private String generationParams;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
