package com.manga.ai.shot.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 分镜视频资产版本VO
 */
@Data
public class ShotVideoAssetVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long shotId;
    private Integer version;
    private String videoUrl;
    private String thumbnailUrl;
    private Boolean isActive;
    private LocalDateTime createdAt;

    /**
     * 生成模型
     */
    private String model;

    /**
     * 生成提示词
     */
    private String prompt;

    /**
     * 参考图URLs
     */
    private String referenceUrls;

    /**
     * 完整生成参数
     */
    private String generationParams;
}
