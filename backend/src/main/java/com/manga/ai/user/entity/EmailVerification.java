package com.manga.ai.user.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 邮箱验证码实体
 */
@Data
@TableName("email_verification")
public class EmailVerification implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 邮箱
     */
    private String email;

    /**
     * 验证码
     */
    private String code;

    /**
     * 类型：register-注册，reset-重置密码
     */
    private String type;

    /**
     * 过期时间
     */
    private LocalDateTime expiredAt;

    /**
     * 是否已使用：0-未使用，1-已使用
     */
    private Integer used;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
