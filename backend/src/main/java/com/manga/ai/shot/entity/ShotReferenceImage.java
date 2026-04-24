package com.manga.ai.shot.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 分镜参考图实体
 */
@Data
@TableName("shot_reference_image")
public class ShotReferenceImage implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 分镜ID
     */
    private Long shotId;

    /**
     * 图片类型: scene, role, prop
     */
    private String imageType;

    /**
     * 对应资产ID
     */
    private Long referenceId;

    /**
     * 资产名称
     */
    private String referenceName;

    /**
     * 图片URL
     */
    private String imageUrl;

    /**
     * 显示顺序
     */
    private Integer displayOrder;

    /**
     * 是否用户手动添加
     */
    private Integer isUserAdded;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
