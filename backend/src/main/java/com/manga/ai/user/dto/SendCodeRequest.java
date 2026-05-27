package com.manga.ai.user.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 发送验证码请求
 */
@Data
public class SendCodeRequest {

    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /**
     * 兼容旧邮箱验证码请求，不再用于新注册。
     */
    private String email;

    /**
     * 类型：register-注册，reset-重置密码
     */
    @NotBlank(message = "类型不能为空")
    private String type;
}
