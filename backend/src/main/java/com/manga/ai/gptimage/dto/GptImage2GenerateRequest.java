package com.manga.ai.gptimage.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * GPT-Image2 图片生成请求
 */
@Data
public class GptImage2GenerateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户提示词
     */
    private String prompt;

    /**
     * 图片比例
     */
    private String aspectRatio;

    /**
     * 清晰度：1k / 2k / 4k
     */
    private String resolution;

    /**
     * 可选参考图 URL，存在时执行图生图
     */
    private String referenceImageUrl;
}
