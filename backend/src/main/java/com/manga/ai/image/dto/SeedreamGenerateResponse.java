package com.manga.ai.image.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * Seedream 生成响应
 */
@Data
public class SeedreamGenerateResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 任务ID
     */
    private String taskId;

    /**
     * 图片数据 (Base64)
     */
    private String imageData;

    /**
     * 图片URL
     */
    private String imageUrl;

    /**
     * Seed
     */
    private Long seed;

    /**
     * 宽度
     */
    private Integer width;

    /**
     * 高度
     */
    private Integer height;

    /**
     * 状态
     */
    private String status;

    /**
     * 错误信息
     */
    private String errorMessage;
}
