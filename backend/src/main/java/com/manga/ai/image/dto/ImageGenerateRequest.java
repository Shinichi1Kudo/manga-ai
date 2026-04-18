package com.manga.ai.image.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 图像生成请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageGenerateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 角色名称
     */
    private String roleName;

    /**
     * 角色描述（外貌、服装等）
     */
    private String characterDescription;

    /**
     * 图片比例: 16:9 / 3:4
     */
    private String aspectRatio;

    /**
     * 清晰度: standard / hd / ultra
     */
    private String quality;

    /**
     * 风格关键词
     */
    private String styleKeywords;

    /**
     * 系列ID
     */
    private Long seriesId;

    /**
     * 角色ID
     */
    private Long roleId;

    /**
     * 自定义提示词
     */
    private String customPrompt;

    /**
     * 服装编号（用于多服装支持）
     */
    private Integer clothingId;

    /**
     * 参考图片URL（用于图生图）
     */
    private String referenceImageUrl;

    /**
     * 新服装描述（用于生成新服装）
     */
    private String clothingPrompt;
}
