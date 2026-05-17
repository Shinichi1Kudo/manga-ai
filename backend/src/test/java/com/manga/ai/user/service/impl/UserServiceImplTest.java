package com.manga.ai.user.service.impl;

import com.manga.ai.user.dto.SendCodeRequest;
import com.manga.ai.user.mapper.EmailVerificationMapper;
import com.manga.ai.user.mapper.UserMapper;
import com.manga.ai.user.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class UserServiceImplTest {

    @Test
    void sendCodeUsesHaidaiSiteNameInEmailSubject() {
        UserMapper userMapper = mock(UserMapper.class);
        EmailVerificationMapper emailVerificationMapper = mock(EmailVerificationMapper.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        UserServiceImpl service = new UserServiceImpl(
                userMapper,
                emailVerificationMapper,
                mock(JwtUtil.class),
                mock(TokenServiceImpl.class)
        );
        ReflectionTestUtils.setField(service, "mailSender", mailSender);
        ReflectionTestUtils.setField(service, "fromEmail", "noreply@example.com");

        SendCodeRequest request = new SendCodeRequest();
        request.setEmail("user@example.com");
        request.setType("register");

        service.sendCode(request);

        verify(mailSender).send(argThat((SimpleMailMessage message) ->
                "【海带 AI 内容智能创作平台】验证码".equals(message.getSubject())));
    }
}
