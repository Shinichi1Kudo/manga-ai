package com.manga.ai.asset.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 资产元数据实体
 */
@Data
@TableName("asset_metadata")
public class AssetMetadata implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 资产ID
     */
    private Long assetId;

    /**
     * 生成用Prompt（系统构建后的完整提示词）
     */
    private String prompt;

    /**
     * 用户原始输入的提示词
     */
    private String userPrompt;

    /**
     * 负向Prompt
     */
    private String negativePrompt;

    /**
     * Seed值
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
     * 宽高比
     */
    private String aspectRatio;

    /**
     * 是否使用精细三视图模式生成
     */
    private Boolean detailedView;

    /**
     * 生成耗时(毫秒)
     */
    private Long generationTimeMs;

    /**
     * API原始响应JSON
     */
    private String apiResponse;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
