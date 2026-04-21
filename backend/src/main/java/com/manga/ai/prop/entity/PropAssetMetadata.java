package com.manga.ai.prop.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 道具资产元数据实体
 */
@Data
@TableName("prop_asset_metadata")
public class PropAssetMetadata implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 资产ID
     */
    private Long assetId;

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
     * 图片宽度
     */
    private Integer imageWidth;

    /**
     * 图片高度
     */
    private Integer imageHeight;

    /**
     * 图片比例
     */
    private String aspectRatio;

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
