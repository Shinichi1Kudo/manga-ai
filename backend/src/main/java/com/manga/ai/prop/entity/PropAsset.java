package com.manga.ai.prop.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 道具资产实体
 */
@Data
@TableName("prop_asset")
public class PropAsset implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 道具ID
     */
    private Long propId;

    /**
     * 资产类型
     */
    private String assetType;

    /**
     * 视图类型
     */
    private String viewType;

    /**
     * 版本号
     */
    private Integer version;

    /**
     * 图片路径
     */
    private String filePath;

    /**
     * 透明PNG路径
     */
    private String transparentPath;

    /**
     * 缩略图路径
     */
    private String thumbnailPath;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 状态
     */
    private Integer status;

    /**
     * 是否当前激活版本
     */
    private Integer isActive;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
