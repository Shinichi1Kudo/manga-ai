package com.manga.ai.shot.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 视频资产元数据实体
 */
@Data
@TableName("video_metadata")
public class VideoMetadata implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 分镜ID
     */
    private Long shotId;

    /**
     * 完整提示词
     */
    private String prompt;

    /**
     * 用户提示词
     */
    private String userPrompt;

    /**
     * 负面提示词
     */
    private String negativePrompt;

    /**
     * 生成Seed
     */
    private Long seed;

    /**
     * 模型版本
     */
    private String modelVersion;

    /**
     * 视频时长(秒)
     */
    private Integer videoDuration;

    /**
     * 视频宽度
     */
    private Integer videoWidth;

    /**
     * 视频高度
     */
    private Integer videoHeight;

    /**
     * 生成耗时(毫秒)
     */
    private Long generationTimeMs;

    /**
     * API响应JSON
     */
    private String apiResponse;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
