package com.manga.ai.role.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 角色重新生成请求
 */
@Data
public class RegenerateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 要重新生成的视图类型列表
     */
    private List<String> viewTypes;

    /**
     * 服装编号
     */
    private Integer clothingId;

    /**
     * 服装名称
     */
    private String clothingName;

    /**
     * 修改的 Prompt
     */
    private String modifiedPrompt;

    /**
     * 是否保持 Seed
     */
    private Boolean keepSeed = true;

    /**
     * 是否生成新服装
     * true: 生成新服装（不改变角色状态）
     * false: 重新生成当前服装（改变角色状态为生成中）
     */
    private Boolean isNewClothing = false;

    /**
     * 参考图片URL（用于图生图）
     */
    private String referenceImageUrl;

    /**
     * 图片比例: 1:1 / 3:4 / 4:3 / 16:9 / 9:16 / 2:3 / 3:2 / 21:9
     */
    private String aspectRatio;

    /**
     * 清晰度: hd (高清) / uhd (超清)
     */
    private String quality;

    /**
     * 风格关键词（预设风格会转换为具体提示词）
     */
    private String styleKeywords;
}
