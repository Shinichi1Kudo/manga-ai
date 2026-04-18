package com.manga.ai.role.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 角色属性实体
 */
@Data
@TableName("role_attribute")
public class RoleAttribute implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 角色ID
     */
    private Long roleId;

    /**
     * 属性键
     */
    private String attrKey;

    /**
     * 属性值
     */
    private String attrValue;

    /**
     * 提取置信度
     */
    private BigDecimal confidence;

    /**
     * 来源类型
     */
    private String sourceType;

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
