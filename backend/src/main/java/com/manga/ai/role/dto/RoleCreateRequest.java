package com.manga.ai.role.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 角色创建请求
 */
@Data
public class RoleCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 系列ID
     */
    @NotNull(message = "系列ID不能为空")
    private Long seriesId;

    /**
     * 角色名称
     */
    @NotBlank(message = "角色名称不能为空")
    private String roleName;

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
     * 自定义提示词
     */
    private String customPrompt;
}
