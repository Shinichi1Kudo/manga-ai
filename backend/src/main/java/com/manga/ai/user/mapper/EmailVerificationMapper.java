package com.manga.ai.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.manga.ai.user.entity.EmailVerification;
import org.apache.ibatis.annotations.Mapper;

/**
 * 邮箱验证码 Mapper
 */
@Mapper
public interface EmailVerificationMapper extends BaseMapper<EmailVerification> {
}
