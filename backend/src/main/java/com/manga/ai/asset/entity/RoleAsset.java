package com.manga.ai.asset.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 角色资产实体
 */
@Data
@TableName("role_asset")
public class RoleAsset implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 角色ID
     */
    private Long roleId;

    /**
     * 资产类型
     */
    private String assetType;

    /**
     * 视图类型
     */
    private String viewType;

    /**
     * 服装编号
     */
    private Integer clothingId;

    /**
     * 服装名称
     */
    private String clothingName;

    /**
     * 版本号
     */
    private Integer version;

    /**
     * 原始图片路径
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
     * 规范校验是否通过
     */
    private Integer validationPassed;

    /**
     * 规范校验结果JSON
     */
    private String validationResult;

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

    /**
     * 是否使用精细三视图模式生成（来自元数据，非数据库字段）
     */
    @TableField(exist = false)
    private Boolean detailedView;
}
