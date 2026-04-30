package com.manga.ai.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.user.dto.*;
import com.manga.ai.user.entity.EmailVerification;
import com.manga.ai.user.entity.User;
import com.manga.ai.user.mapper.EmailVerificationMapper;
import com.manga.ai.user.mapper.UserMapper;
import com.manga.ai.user.service.CreditRecordService;
import com.manga.ai.user.service.UserService;
import com.manga.ai.user.util.JwtUtil;
import com.manga.ai.user.service.TokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Random;

/**
 * 用户服务实现
 */
@Slf4j
@Service
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final EmailVerificationMapper emailVerificationMapper;
    private final JwtUtil jwtUtil;
    private final TokenService tokenService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Autowired
    @Lazy
    private CreditRecordService creditRecordService;

    @Value("${spring.mail.username:}")
    private String fromEmail;

    // 验证码有效期（分钟）
    private static final int CODE_EXPIRE_MINUTES = 10;

    public UserServiceImpl(UserMapper userMapper, EmailVerificationMapper emailVerificationMapper, JwtUtil jwtUtil, TokenService tokenService) {
        this.userMapper = userMapper;
        this.emailVerificationMapper = emailVerificationMapper;
        this.jwtUtil = jwtUtil;
        this.tokenService = tokenService;
    }

    @Override
    public void sendCode(SendCodeRequest request) {
        String email = request.getEmail();
        String type = request.getType();

        // 检查邮件服务是否可用
        if (mailSender == null || fromEmail == null || fromEmail.isEmpty()) {
            throw new BusinessException("邮件服务未配置，请联系管理员");
        }

        // 检查邮箱是否已注册（注册时）
        if ("register".equals(type)) {
            User existUser = getByEmail(email);
            if (existUser != null) {
                throw new BusinessException("该邮箱已注册");
            }
        }

        // 检查邮箱是否存在（重置密码时）
        if ("reset".equals(type)) {
            User existUser = getByEmail(email);
            if (existUser == null) {
                throw new BusinessException("该邮箱未注册");
            }
        }

        // 生成6位验证码
        String code = generateCode();

        // 保存验证码
        EmailVerification verification = new EmailVerification();
        verification.setEmail(email);
        verification.setCode(code);
        verification.setType(type);
        verification.setExpiredAt(LocalDateTime.now().plusMinutes(CODE_EXPIRE_MINUTES));
        verification.setUsed(0);
        verification.setCreatedAt(LocalDateTime.now());
        emailVerificationMapper.insert(verification);

        // 发送邮件
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(email);
            message.setSubject("【漫剧AI】验证码");
            message.setText("您的验证码是：" + code + "，有效期" + CODE_EXPIRE_MINUTES + "分钟，请勿泄露给他人。");
            mailSender.send(message);
            log.info("验证码发送成功: email={}", email);
        } catch (Exception e) {
            log.error("验证码发送失败: email={}", email, e);
            throw new BusinessException("验证码发送失败，请稍后重试");
        }
    }

    @Override
    public UserVO register(RegisterRequest request) {
        String email = request.getEmail();
        String code = request.getCode();
        String password = request.getPassword();

        // 验证验证码
        if (!verifyCode(email, code, "register")) {
            throw new BusinessException("验证码错误或已过期");
        }

        // 检查邮箱是否已注册
        User existUser = getByEmail(email);
        if (existUser != null) {
            throw new BusinessException("该邮箱已注册");
        }

        // 创建用户
        User user = new User();
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname(request.getNickname() != null ? request.getNickname() : email.split("@")[0]);
        user.setCredits(10); // 新用户赠送10积分
        user.setStatus(1);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);

        log.info("用户注册成功: userId={}, email={}", user.getId(), email);

        // 生成 Token
        String token = jwtUtil.generateToken(user.getId(), user.getEmail());

        // 存入Redis
        tokenService.storeToken(token, user.getId());

        return convertToVO(user, token);
    }

    @Override
    public UserVO login(LoginRequest request) {
        String email = request.getEmail();
        String password = request.getPassword();

        // 查找用户
        User user = getByEmail(email);
        if (user == null) {
            throw new BusinessException("邮箱或密码错误");
        }

        // 验证密码
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException("邮箱或密码错误");
        }

        // 检查状态
        if (user.getStatus() != 1) {
            throw new BusinessException("账号已被禁用");
        }

        // 更新最后登录时间
        updateLastLogin(user.getId());

        // 生成 Token
        String token = jwtUtil.generateToken(user.getId(), user.getEmail());

        // 存入Redis
        tokenService.storeToken(token, user.getId());

        log.info("用户登录成功: userId={}, email={}", user.getId(), email);

        return convertToVO(user, token);
    }

    @Override
    public User getById(Long id) {
        return userMapper.selectById(id);
    }

    @Override
    public User getByEmail(String email) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getEmail, email);
        return userMapper.selectOne(wrapper);
    }

    @Override
    public User getCurrentUser() {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return null;
        }
        return getById(userId);
    }

    @Override
    public Long getCurrentUserId() {
        // 从 ThreadLocal 或 Request 获取，由拦截器设置
        return UserContextHolder.getUserId();
    }

    @Override
    public void updateLastLogin(Long userId) {
        User user = new User();
        user.setId(userId);
        user.setLastLoginAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
    }

    /**
     * 验证验证码
     */
    private boolean verifyCode(String email, String code, String type) {
        LambdaQueryWrapper<EmailVerification> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(EmailVerification::getEmail, email)
               .eq(EmailVerification::getCode, code)
               .eq(EmailVerification::getType, type)
               .eq(EmailVerification::getUsed, 0)
               .gt(EmailVerification::getExpiredAt, LocalDateTime.now())
               .orderByDesc(EmailVerification::getCreatedAt)
               .last("LIMIT 1");

        EmailVerification verification = emailVerificationMapper.selectOne(wrapper);
        if (verification == null) {
            return false;
        }

        // 标记为已使用
        verification.setUsed(1);
        emailVerificationMapper.updateById(verification);

        return true;
    }

    /**
     * 生成6位数字验证码
     */
    private String generateCode() {
        Random random = new Random();
        int code = 100000 + random.nextInt(900000);
        return String.valueOf(code);
    }

    /**
     * 转换为 VO
     */
    private UserVO convertToVO(User user, String token) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setEmail(user.getEmail());
        vo.setNickname(user.getNickname());
        vo.setCredits(user.getCredits());
        vo.setStatus(user.getStatus());
        vo.setCreatedAt(user.getCreatedAt());
        vo.setLastLoginAt(user.getLastLoginAt());
        vo.setAvatar(user.getAvatar());
        vo.setToken(token);
        return vo;
    }

    @Override
    public void deductCredits(Long userId, int amount) {
        deductCredits(userId, amount, null, null, null, null);
    }

    @Override
    public void deductCredits(Long userId, int amount, String usageType, String description) {
        deductCredits(userId, amount, usageType, description, null, null);
    }

    @Override
    public void deductCredits(Long userId, int amount, String usageType, String description, Long referenceId, String referenceType) {
        if (amount <= 0) {
            throw new BusinessException("扣除积分数量必须大于0");
        }

        // 使用条件UPDATE实现原子操作，防止并发问题
        // UPDATE user SET credits = credits - ? WHERE id = ? AND credits >= ?
        LambdaUpdateWrapper<User> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(User::getId, userId)
               .ge(User::getCredits, amount)
               .setSql("credits = credits - " + amount)
               .set(User::getUpdatedAt, LocalDateTime.now());

        int rows = userMapper.update(null, wrapper);
        if (rows == 0) {
            User user = userMapper.selectById(userId);
            if (user == null) {
                throw new BusinessException("用户不存在");
            }
            throw new BusinessException("积分不足，需要" + amount + "积分，当前" + user.getCredits() + "积分");
        }

        log.info("积分扣除成功: userId={}, amount={}", userId, amount);

        // 记录积分扣除
        if (usageType != null && description != null && creditRecordService != null) {
            creditRecordService.recordDeduction(userId, amount, usageType, description, referenceId, referenceType);
        }
    }

    @Override
    public void refundCredits(Long userId, int amount) {
        refundCredits(userId, amount, null, null, null);
    }

    @Override
    public void refundCredits(Long userId, int amount, String description) {
        refundCredits(userId, amount, description, null, null);
    }

    @Override
    public void refundCredits(Long userId, int amount, String description, Long referenceId, String referenceType) {
        if (amount <= 0) {
            return;
        }

        LambdaUpdateWrapper<User> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(User::getId, userId)
               .setSql("credits = credits + " + amount)
               .set(User::getUpdatedAt, LocalDateTime.now());

        userMapper.update(null, wrapper);
        log.info("积分返还成功: userId={}, amount={}", userId, amount);

        // 记录积分返还
        if (description != null && creditRecordService != null) {
            creditRecordService.recordRefund(userId, amount, description, referenceId, referenceType);
        }
    }

    @Override
    public Integer getUserCredits(Long userId) {
        User user = userMapper.selectById(userId);
        return user != null ? user.getCredits() : null;
    }

    @Override
    public boolean hasSufficientCredits(Long userId, int amount) {
        User user = userMapper.selectById(userId);
        return user != null && user.getCredits() != null && user.getCredits() >= amount;
    }

    @Override
    public void updateProfile(Long userId, String nickname, String avatar) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        // 更新昵称
        if (nickname != null && !nickname.trim().isEmpty()) {
            user.setNickname(nickname.trim());
        }

        // 更新头像
        if (avatar != null) {
            user.setAvatar(avatar);
        }

        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);

        log.info("用户资料更新成功: userId={}, nickname={}, avatar={}", userId, nickname, avatar != null ? "已更新" : "未更新");
    }

    @Override
    public boolean isNicknameAvailable(String nickname, Long currentUserId) {
        if (nickname == null || nickname.trim().isEmpty()) {
            return false;
        }

        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getNickname, nickname.trim());

        // 排除当前用户
        if (currentUserId != null) {
            wrapper.ne(User::getId, currentUserId);
        }

        User existingUser = userMapper.selectOne(wrapper);
        return existingUser == null;
    }

    /**
     * 用户上下文持有者（ThreadLocal）
     */
    public static class UserContextHolder {
        private static final ThreadLocal<Long> userIdHolder = new ThreadLocal<>();

        public static void setUserId(Long userId) {
            userIdHolder.set(userId);
        }

        public static Long getUserId() {
            return userIdHolder.get();
        }

        public static void clear() {
            userIdHolder.remove();
        }
    }
}
