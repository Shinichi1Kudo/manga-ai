package com.manga.ai.shot.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 参考图 DTO
 */
@Data
public class ReferenceImageDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

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
    private Boolean isUserAdded;
}
