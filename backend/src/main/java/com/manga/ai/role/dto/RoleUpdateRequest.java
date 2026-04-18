package com.manga.ai.role.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 角色更新请求
 */
@Data
public class RoleUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String roleName;

    @Size(max = 20, message = "年龄不能超过20字符")
    private String age;

    @Size(max = 10, message = "性别不能超过10字符")
    private String gender;

    @Size(max = 2000, message = "外貌描述不能超过2000字符")
    private String appearance;

    @Size(max = 2000, message = "性格描述不能超过2000字符")
    private String personality;

    @Size(max = 2000, message = "服装描述不能超过2000字符")
    private String clothing;

    @Size(max = 1000, message = "特殊标识不能超过1000字符")
    private String specialMarks;

    /**
     * 自定义提示词
     */
    @Size(max = 4000, message = "自定义提示词不能超过4000字符")
    private String customPrompt;
}
