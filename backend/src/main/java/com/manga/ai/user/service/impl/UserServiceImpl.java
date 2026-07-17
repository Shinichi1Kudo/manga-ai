package com.manga.ai.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.user.dto.*;
import com.manga.ai.user.entity.EmailVerification;
import com.manga.ai.user.entity.User;
import com.manga.ai.user.mapper.EmailVerificationMapper;
import com.manga.ai.user.mapper.UserMapper;
import com.manga.ai.payment.service.CreditAccountService;
import com.manga.ai.payment.service.MembershipService;
import com.manga.ai.user.service.CreditRecordService;
import com.manga.ai.user.service.SmsSender;
import com.manga.ai.user.service.UserService;
import com.manga.ai.user.util.JwtUtil;
import com.manga.ai.user.service.TokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
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
    private final MembershipService membershipService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Autowired(required = false)
    private SmsSender smsSender;

    @Autowired
    @Lazy
    private CreditRecordService creditRecordService;

    @Autowired(required = false)
    @Lazy
    private CreditAccountService creditAccountService;

    // 验证码有效期（分钟）
    private static final int CODE_EXPIRE_MINUTES = 10;
    private static final int NEW_USER_REWARD_CREDITS = 20;

    public UserServiceImpl(UserMapper userMapper, EmailVerificationMapper emailVerificationMapper, JwtUtil jwtUtil,
                           TokenService tokenService, MembershipService membershipService) {
        this.userMapper = userMapper;
        this.emailVerificationMapper = emailVerificationMapper;
        this.jwtUtil = jwtUtil;
        this.tokenService = tokenService;
        this.membershipService = membershipService;
    }

    @Override
    public void sendCode(SendCodeRequest request) {
        String phone = normalizePhone(request.getPhone());
        String type = request.getType();

        if (isBlank(phone)) {
            throw new BusinessException("请输入手机号");
        }
        if (smsSender == null) {
            throw new BusinessException("短信服务未配置，请联系管理员");
        }

        // 检查手机号是否已注册（注册时）
        if ("register".equals(type)) {
            User existUser = getByPhone(phone);
            if (existUser != null) {
                throw new BusinessException("该手机号已注册");
            }
        }

        // 检查手机号是否存在（登录或重置密码时）
        if ("login".equals(type) || "reset".equals(type)) {
            User existUser = getByPhone(phone);
            if (existUser == null) {
                throw new BusinessException("该手机号未注册");
            }
        }

        // 生成6位验证码
        String code = generateCode();

        smsSender.sendVerificationCode(phone, code, type);

        // 发送成功后再保存验证码，避免短信通道失败时留下可用验证码。
        EmailVerification verification = new EmailVerification();
        verification.setPhone(phone);
        verification.setCode(code);
        verification.setType(type);
        verification.setExpiredAt(LocalDateTime.now().plusMinutes(CODE_EXPIRE_MINUTES));
        verification.setUsed(0);
        verification.setCreatedAt(LocalDateTime.now());
        emailVerificationMapper.insert(verification);

        log.info("短信验证码发送成功: phone={}, type={}", maskPhone(phone), type);
    }

    @Override
    public UserVO register(RegisterRequest request) {
        String phone = normalizePhone(request.getPhone());
        String code = request.getCode();
        String password = request.getPassword();

        // 验证验证码
        if (!verifyCode(phone, null, code, "register")) {
            throw new BusinessException("验证码错误或已过期");
        }

        // 检查手机号是否已注册
        User existUser = getByPhone(phone);
        if (existUser != null) {
            throw new BusinessException("该手机号已注册");
        }

        // 创建用户
        User user = new User();
        user.setPhone(phone);
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname(!isBlank(request.getNickname()) ? request.getNickname().trim() : "用户" + phone.substring(phone.length() - 4));
        user.setCredits(NEW_USER_REWARD_CREDITS);
        user.setStatus(1);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);

        log.info("用户注册成功: userId={}, phone={}", user.getId(), maskPhone(phone));

        return issueTokenAndConvert(user);
    }

    @Override
    public UserVO login(LoginRequest request) {
        String account = !isBlank(request.getAccount()) ? request.getAccount().trim() : request.getEmail();
        String password = request.getPassword();
        String loginType = isBlank(request.getLoginType()) ? "password" : request.getLoginType();

        User user;
        if ("sms".equals(loginType)) {
            String phone = normalizePhone(account);
            if (!verifyCode(phone, null, request.getCode(), "login")) {
                throw new BusinessException("验证码错误或已过期");
            }
            user = getByPhone(phone);
            if (user == null) {
                throw new BusinessException("手机号未注册");
            }
        } else {
            user = getByAccount(account);
            if (user == null) {
                throw new BusinessException("账号或密码错误");
            }
            if (isBlank(password) || !passwordEncoder.matches(password, user.getPassword())) {
                throw new BusinessException("账号或密码错误");
            }
        }

        // 检查状态
        if (user == null) {
            throw new BusinessException("账号不存在");
        }
        if (user.getStatus() != 1) {
            throw new BusinessException("账号已被禁用");
        }

        // 更新最后登录时间
        updateLastLogin(user.getId());

        log.info("用户登录成功: userId={}, account={}", user.getId(), maskAccount(account));

        return issueTokenAndConvert(user);
    }

    @Override
    public User getById(Long id) {
        return userMapper.selectById(id);
    }

    @Override
    public User getByEmail(String email) {
        if (isBlank(email)) {
            return null;
        }
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getEmail, email);
        return userMapper.selectOne(wrapper);
    }

    @Override
    public User getByPhone(String phone) {
        if (isBlank(phone)) {
            return null;
        }
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getPhone, normalizePhone(phone));
        return userMapper.selectOne(wrapper);
    }

    @Override
    public UserVO bindPhone(Long userId, BindPhoneRequest request) {
        String phone = normalizePhone(request.getPhone());
        if (!verifyCode(phone, null, request.getCode(), "bind")) {
            throw new BusinessException("验证码错误或已过期");
        }

        User existing = getByPhone(phone);
        if (existing != null && !existing.getId().equals(userId)) {
            throw new BusinessException("该手机号已绑定其他账号");
        }

        User update = new User();
        update.setId(userId);
        update.setPhone(phone);
        update.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(update);

        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        user.setPhone(phone);
        log.info("用户绑定手机号成功: userId={}, phone={}", userId, maskPhone(phone));
        return issueTokenAndConvert(user);
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
    private boolean verifyCode(String phone, String email, String code, String type) {
        LambdaQueryWrapper<EmailVerification> wrapper = new LambdaQueryWrapper<>();
        if (!isBlank(phone)) {
            wrapper.eq(EmailVerification::getPhone, normalizePhone(phone));
        } else {
            wrapper.eq(EmailVerification::getEmail, email);
        }
        wrapper.eq(EmailVerification::getCode, code)
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
        vo.setPhone(user.getPhone());
        vo.setNickname(user.getNickname());
        vo.setCredits(user.getCredits());
        vo.setStatus(user.getStatus());
        vo.setRequiresPhoneBinding(isBlank(user.getPhone()));
        vo.setCreatedAt(user.getCreatedAt());
        vo.setLastLoginAt(user.getLastLoginAt());
        vo.setAvatar(user.getAvatar());
        vo.setToken(token);
        vo.setMembership(MembershipSummaryVO.from(membershipService.getActiveMembership(user.getId())));
        return vo;
    }

    private UserVO issueTokenAndConvert(User user) {
        String tokenSubject = !isBlank(user.getEmail()) ? user.getEmail() : user.getPhone();
        String token = jwtUtil.generateToken(user.getId(), tokenSubject);
        tokenService.storeToken(token, user.getId());
        return convertToVO(user, token);
    }

    private User getByAccount(String account) {
        if (isBlank(account)) {
            return null;
        }
        String normalized = account.trim();
        if (isEmail(normalized)) {
            return getByEmail(normalized);
        }
        return getByPhone(normalized);
    }

    private boolean isEmail(String value) {
        return value != null && value.contains("@");
    }

    private String normalizePhone(String phone) {
        if (phone == null) {
            return "";
        }
        String normalized = phone.trim();
        if (normalized.startsWith("+86")) {
            normalized = normalized.substring(3);
        } else if (normalized.startsWith("0086")) {
            normalized = normalized.substring(4);
        } else if (normalized.startsWith("86") && normalized.length() == 13) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private String maskAccount(String account) {
        if (isEmail(account)) {
            int at = account.indexOf('@');
            return at <= 1 ? "***" + account.substring(at) : account.charAt(0) + "***" + account.substring(at);
        }
        return maskPhone(account);
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
        if (creditAccountService != null) {
            creditAccountService.consumeCredits(userId, amount, usageType, description, referenceId, referenceType);
            return;
        }

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
