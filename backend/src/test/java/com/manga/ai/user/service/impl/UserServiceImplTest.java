package com.manga.ai.user.service.impl;

import com.manga.ai.user.dto.SendCodeRequest;
import com.manga.ai.user.dto.RegisterRequest;
import com.manga.ai.user.dto.UserVO;
import com.manga.ai.user.entity.EmailVerification;
import com.manga.ai.user.entity.User;
import com.manga.ai.user.mapper.EmailVerificationMapper;
import com.manga.ai.user.mapper.UserMapper;
import com.manga.ai.user.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceImplTest {

    @Test
    void registerRewardsNewUserWithTwentyCredits() {
        UserMapper userMapper = mock(UserMapper.class);
        EmailVerificationMapper emailVerificationMapper = mock(EmailVerificationMapper.class);
        JwtUtil jwtUtil = mock(JwtUtil.class);
        TokenServiceImpl tokenService = mock(TokenServiceImpl.class);
        UserServiceImpl service = new UserServiceImpl(
                userMapper,
                emailVerificationMapper,
                jwtUtil,
                tokenService
        );

        EmailVerification verification = new EmailVerification();
        verification.setEmail("new@example.com");
        verification.setCode("123456");
        verification.setType("register");
        verification.setUsed(0);
        when(emailVerificationMapper.selectOne(any())).thenReturn(verification);
        when(userMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(88L);
            return 1;
        }).when(userMapper).insert(any(User.class));
        when(jwtUtil.generateToken(88L, "new@example.com")).thenReturn("token-88");

        RegisterRequest request = new RegisterRequest();
        request.setEmail("new@example.com");
        request.setCode("123456");
        request.setPassword("password123");
        request.setNickname("新用户");

        UserVO result = service.register(request);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(userCaptor.capture());
        assertThat(userCaptor.getValue().getCredits()).isEqualTo(20);
        assertThat(result.getCredits()).isEqualTo(20);
        verify(tokenService).storeToken("token-88", 88L);
    }

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
