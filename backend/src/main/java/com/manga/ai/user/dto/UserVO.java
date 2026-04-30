package com.manga.ai.user.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户信息 VO
 */
@Data
public class UserVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String email;
    private String nickname;
    private Integer credits;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime lastLoginAt;

    /**
     * 头像URL
     */
    private String avatar;

    /**
     * JWT Token（登录/注册时返回）
     */
    private String token;
}
