package com.manga.ai.video.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * Seedance视频生成请求
 */
@Data
public class SeedanceRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 提示词
     */
    private String prompt;

    /**
     * 参考图片URL（可选，用于图生视频）
     */
    private String referenceImageUrl;

    /**
     * 视频时长（秒），最大15秒
     */
    private Integer duration = 5;

    /**
     * 视频宽度
     */
    private Integer width = 1280;

    /**
     * 视频高度
     */
    private Integer height = 720;

    /**
     * 种子值
     */
    private Long seed;

    /**
     * 分镜ID（用于关联）
     */
    private Long shotId;
}
