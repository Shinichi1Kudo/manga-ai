package com.manga.ai.image.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Seedream 生成请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeedreamGenerateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Prompt
     */
    private String prompt;

    /**
     * 负向 Prompt
     */
    private String negativePrompt;

    /**
     * 宽高比: 16:9 / 3:4
     */
    private String aspectRatio;

    /**
     * 模型版本
     */
    private String modelVersion;

    /**
     * Seed
     */
    private Long seed;

    /**
     * 参考图片 (Base64)
     */
    private String referenceImage;

    /**
     * 参考强度
     */
    private Double referenceStrength;
}
