package com.manga.ai.role.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 角色实体
 */
@Data
@TableName("role")
public class Role implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 系列ID
     */
    private Long seriesId;

    /**
     * 角色名称
     */
    private String roleName;

    /**
     * 角色编码
     */
    private String roleCode;

    /**
     * 状态: 0-提取中 1-待审核 2-已确认 3-已锁定
     */
    private Integer status;

    /**
     * 年龄
     */
    private String age;

    /**
     * 性别
     */
    private String gender;

    /**
     * 外貌描述
     */
    private String appearance;

    /**
     * 性格描述
     */
    private String personality;

    /**
     * 服装描述
     */
    private String clothing;

    /**
     * 特殊标识
     */
    private String specialMarks;

    /**
     * 自定义提示词（用于图片生成）
     */
    private String customPrompt;

    /**
     * 风格关键词
     */
    private String styleKeywords;

    /**
     * 原始人物介绍片段
     */
    private String originalText;

    /**
     * NLP提取置信度
     */
    private BigDecimal extractConfidence;

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
     * 是否删除
     */
    @TableLogic
    private Integer isDeleted;

    /**
     * 扩展属性列表
     */
    @TableField(exist = false)
    private List<RoleAttribute> attributes;

    /**
     * 资产列表
     */
    @TableField(exist = false)
    private List<Long> assetIds;
}
