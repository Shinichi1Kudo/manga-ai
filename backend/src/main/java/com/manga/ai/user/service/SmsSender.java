package com.manga.ai.user.service;

/**
 * 短信验证码发送器。
 */
public interface SmsSender {

    void sendVerificationCode(String phone, String code, String type);
}
