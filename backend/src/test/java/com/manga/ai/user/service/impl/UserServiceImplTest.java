package com.manga.ai.user.service.impl;

import com.manga.ai.user.dto.SendCodeRequest;
import com.manga.ai.user.dto.BindPhoneRequest;
import com.manga.ai.user.dto.LoginRequest;
import com.manga.ai.user.dto.RegisterRequest;
import com.manga.ai.user.dto.UserVO;
import com.manga.ai.user.entity.EmailVerification;
import com.manga.ai.user.entity.User;
import com.manga.ai.user.mapper.EmailVerificationMapper;
import com.manga.ai.user.mapper.UserMapper;
import com.manga.ai.user.service.SmsSender;
import com.manga.ai.user.util.JwtUtil;
import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.payment.entity.UserMembership;
import com.manga.ai.payment.service.MembershipService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceImplTest {

    @Test
    void loginIncludesActivePaidMembershipSummary() {
        UserMapper userMapper = mock(UserMapper.class);
        JwtUtil jwtUtil = mock(JwtUtil.class);
        MembershipService membershipService = mock(MembershipService.class);
        UserServiceImpl service = new UserServiceImpl(
                userMapper,
                mock(EmailVerificationMapper.class),
                jwtUtil,
                mock(TokenServiceImpl.class),
                membershipService
        );

        User user = new User();
        user.setId(12L);
        user.setPhone("13800138000");
        user.setPassword(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("password123"));
        user.setNickname("高级用户");
        user.setCredits(100);
        user.setStatus(1);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(jwtUtil.generateToken(12L, "13800138000")).thenReturn("token-advanced");

        UserMembership membership = new UserMembership();
        membership.setTier("ADVANCED");
        membership.setExpiresAt(java.time.LocalDateTime.of(2027, 1, 2, 3, 4));
        when(membershipService.getActiveMembership(12L)).thenReturn(membership);

        LoginRequest request = new LoginRequest();
        request.setAccount("13800138000");
        request.setPassword("password123");
        request.setLoginType("password");

        UserVO result = service.login(request);

        assertThat(result.getMembership().getTier()).isEqualTo("ADVANCED");
        assertThat(result.getMembership().getName()).isEqualTo("高级会员");
        assertThat(result.getMembership().getExpiresAt()).isEqualTo(membership.getExpiresAt());
    }

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
                tokenService,
                mock(MembershipService.class)
        );

        EmailVerification verification = new EmailVerification();
        verification.setPhone("13800138000");
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
        when(jwtUtil.generateToken(88L, "13800138000")).thenReturn("token-88");

        RegisterRequest request = new RegisterRequest();
        request.setPhone("13800138000");
        request.setCode("123456");
        request.setPassword("password123");
        request.setNickname("新用户");

        UserVO result = service.register(request);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(userCaptor.capture());
        assertThat(userCaptor.getValue().getPhone()).isEqualTo("13800138000");
        assertThat(userCaptor.getValue().getEmail()).isNull();
        assertThat(userCaptor.getValue().getCredits()).isEqualTo(20);
        assertThat(result.getCredits()).isEqualTo(20);
        assertThat(result.getPhone()).isEqualTo("13800138000");
        verify(tokenService).storeToken("token-88", 88L);
    }

    @Test
    void sendCodeSendsTencentSmsForPhoneRegistration() {
        UserMapper userMapper = mock(UserMapper.class);
        EmailVerificationMapper emailVerificationMapper = mock(EmailVerificationMapper.class);
        SmsSender smsSender = mock(SmsSender.class);
        UserServiceImpl service = new UserServiceImpl(
                userMapper,
                emailVerificationMapper,
                mock(JwtUtil.class),
                mock(TokenServiceImpl.class),
                mock(MembershipService.class)
        );
        ReflectionTestUtils.setField(service, "smsSender", smsSender);

        SendCodeRequest request = new SendCodeRequest();
        request.setPhone("13800138000");
        request.setType("register");

        service.sendCode(request);

        ArgumentCaptor<EmailVerification> verificationCaptor = ArgumentCaptor.forClass(EmailVerification.class);
        verify(emailVerificationMapper).insert(verificationCaptor.capture());
        assertThat(verificationCaptor.getValue().getPhone()).isEqualTo("13800138000");
        assertThat(verificationCaptor.getValue().getEmail()).isNull();
        verify(smsSender).sendVerificationCode(argThat(phone -> phone.equals("13800138000")), any(), argThat(type -> type.equals("register")));
    }

    @Test
    void sendCodeDoesNotPersistVerificationWhenSmsSendFails() {
        UserMapper userMapper = mock(UserMapper.class);
        EmailVerificationMapper emailVerificationMapper = mock(EmailVerificationMapper.class);
        SmsSender smsSender = mock(SmsSender.class);
        UserServiceImpl service = new UserServiceImpl(
                userMapper,
                emailVerificationMapper,
                mock(JwtUtil.class),
                mock(TokenServiceImpl.class),
                mock(MembershipService.class)
        );
        ReflectionTestUtils.setField(service, "smsSender", smsSender);
        doThrow(new BusinessException("短信发送失败")).when(smsSender)
                .sendVerificationCode(argThat(phone -> phone.equals("13800138000")), any(), argThat(type -> type.equals("register")));

        SendCodeRequest request = new SendCodeRequest();
        request.setPhone("13800138000");
        request.setType("register");

        assertThatThrownBy(() -> service.sendCode(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("短信发送失败");

        verify(emailVerificationMapper, never()).insert(any(EmailVerification.class));
    }

    @Test
    void emailPasswordLoginForLegacyUserRequiresPhoneBinding() {
        UserMapper userMapper = mock(UserMapper.class);
        UserServiceImpl service = new UserServiceImpl(
                userMapper,
                mock(EmailVerificationMapper.class),
                mock(JwtUtil.class),
                mock(TokenServiceImpl.class),
                mock(MembershipService.class)
        );

        User user = new User();
        user.setId(9L);
        user.setEmail("legacy@example.com");
        user.setPassword(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("password123"));
        user.setNickname("老用户");
        user.setCredits(30);
        user.setStatus(1);
        when(userMapper.selectOne(any())).thenReturn(user);
        JwtUtil jwtUtil = mock(JwtUtil.class);
        ReflectionTestUtils.setField(service, "jwtUtil", jwtUtil);
        when(jwtUtil.generateToken(9L, "legacy@example.com")).thenReturn("token-legacy");

        LoginRequest request = new LoginRequest();
        request.setAccount("legacy@example.com");
        request.setPassword("password123");
        request.setLoginType("password");

        UserVO result = service.login(request);

        assertThat(result.getRequiresPhoneBinding()).isTrue();
        assertThat(result.getToken()).isEqualTo("token-legacy");
    }

    @Test
    void phonePasswordLoginAllowsBoundUser() {
        UserMapper userMapper = mock(UserMapper.class);
        JwtUtil jwtUtil = mock(JwtUtil.class);
        TokenServiceImpl tokenService = mock(TokenServiceImpl.class);
        UserServiceImpl service = new UserServiceImpl(
                userMapper,
                mock(EmailVerificationMapper.class),
                jwtUtil,
                tokenService,
                mock(MembershipService.class)
        );

        User user = new User();
        user.setId(10L);
        user.setEmail("bound@example.com");
        user.setPhone("13800138000");
        user.setPassword(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("password123"));
        user.setNickname("已绑定");
        user.setCredits(40);
        user.setStatus(1);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(jwtUtil.generateToken(10L, "bound@example.com")).thenReturn("token-bound");

        LoginRequest request = new LoginRequest();
        request.setAccount("13800138000");
        request.setPassword("password123");
        request.setLoginType("password");

        UserVO result = service.login(request);

        assertThat(result.getRequiresPhoneBinding()).isFalse();
        assertThat(result.getPhone()).isEqualTo("13800138000");
        verify(tokenService).storeToken("token-bound", 10L);
    }

    @Test
    void bindPhoneVerifiesSmsCodeAndUpdatesCurrentUser() {
        UserMapper userMapper = mock(UserMapper.class);
        EmailVerificationMapper emailVerificationMapper = mock(EmailVerificationMapper.class);
        JwtUtil jwtUtil = mock(JwtUtil.class);
        TokenServiceImpl tokenService = mock(TokenServiceImpl.class);
        UserServiceImpl service = new UserServiceImpl(
                userMapper,
                emailVerificationMapper,
                jwtUtil,
                tokenService,
                mock(MembershipService.class)
        );

        EmailVerification verification = new EmailVerification();
        verification.setPhone("13800138000");
        verification.setCode("123456");
        verification.setType("bind");
        verification.setUsed(0);
        when(emailVerificationMapper.selectOne(any())).thenReturn(verification);
        when(userMapper.selectOne(any())).thenReturn(null);
        User user = new User();
        user.setId(9L);
        user.setEmail("legacy@example.com");
        user.setNickname("老用户");
        user.setPhone("13800138000");
        user.setCredits(30);
        user.setStatus(1);
        when(userMapper.selectById(9L)).thenReturn(user);
        when(jwtUtil.generateToken(9L, "legacy@example.com")).thenReturn("token-bound-phone");

        BindPhoneRequest request = new BindPhoneRequest();
        request.setPhone("13800138000");
        request.setCode("123456");

        UserVO result = service.bindPhone(9L, request);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(userCaptor.capture());
        assertThat(userCaptor.getValue().getId()).isEqualTo(9L);
        assertThat(userCaptor.getValue().getPhone()).isEqualTo("13800138000");
        assertThat(result.getRequiresPhoneBinding()).isFalse();
        verify(tokenService).storeToken("token-bound-phone", 9L);
    }
}
