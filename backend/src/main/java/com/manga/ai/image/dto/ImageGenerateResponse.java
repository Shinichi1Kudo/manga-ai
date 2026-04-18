package com.manga.ai.image.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 图像生成响应
 */
@Data
public class ImageGenerateResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 任务ID
     */
    private String taskId;

    /**
     * 图片URL
     */
    private String imageUrl;

    /**
     * 图片数据 (Base64)
     */
    private String imageData;

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
     * 状态: success / failed / processing
     */
    private String status;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 资产ID
     */
    private Long assetId;
}
