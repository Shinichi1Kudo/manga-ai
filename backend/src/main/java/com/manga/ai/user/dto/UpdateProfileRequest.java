package com.manga.ai.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 用户资料更新请求
 */
@Data
public class UpdateProfileRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 昵称
     */
    @NotBlank(message = "昵称不能为空")
    @Size(min = 1, max = 20, message = "昵称长度为1-20个字符")
    private String nickname;

    /**
     * 头像URL
     */
    private String avatar;
}
