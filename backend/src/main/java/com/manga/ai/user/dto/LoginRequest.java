package com.manga.ai.user.dto;

import lombok.Data;

/**
 * 登录请求
 */
@Data
public class LoginRequest {

    private String account;

    /**
     * 兼容旧前端字段。
     */
    private String email;

    /**
     * 登录方式：password-密码登录，sms-短信验证码登录。
     */
    private String loginType = "password";

    private String password;

    private String code;
}
