package com.manga.ai.video.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * Seedance视频生成响应
 */
@Data
public class SeedanceResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 响应状态
     */
    private String status;

    /**
     * 任务ID（用于轮询状态）
     */
    private String taskId;

    /**
     * 视频URL
     */
    private String videoUrl;

    /**
     * Provider 原始视频URL（上传到本方OSS前）
     */
    private String providerVideoUrl;

    /**
     * 缩略图URL
     */
    private String thumbnailUrl;

    /**
     * 种子值
     */
    private Long seed;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 生成耗时（毫秒）
     */
    private Long generationTimeMs;

    /**
     * 提交给视频供应商的最终请求地址
     */
    private String submitRequestUrl;

    /**
     * 提交给视频供应商的最终请求体
     */
    private String submitRequestBody;

    /**
     * 提交给视频供应商的最终模型
     */
    private String submitModel;
}
