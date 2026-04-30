package com.manga.ai.user.controller;

import com.manga.ai.common.dto.Result;
import com.manga.ai.user.dto.*;
import com.manga.ai.user.service.TokenService;
import com.manga.ai.user.service.UserService;
import com.manga.ai.user.service.impl.UserServiceImpl.UserContextHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 用户控制器
 */
@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final TokenService tokenService;

    /**
     * 发送验证码
     */
    @PostMapping("/auth/send-code")
    public Result<Void> sendCode(@Valid @RequestBody SendCodeRequest request) {
        userService.sendCode(request);
        return Result.success();
    }

    /**
     * 注册
     */
    @PostMapping("/auth/register")
    public Result<UserVO> register(@Valid @RequestBody RegisterRequest request) {
        UserVO user = userService.register(request);
        return Result.success(user);
    }

    /**
     * 登录
     */
    @PostMapping("/auth/login")
    public Result<UserVO> login(@Valid @RequestBody LoginRequest request) {
        UserVO user = userService.login(request);
        return Result.success(user);
    }

    /**
     * 获取当前用户信息
     */
    @GetMapping("/user/info")
    public Result<UserVO> getUserInfo() {
        var user = userService.getCurrentUser();
        if (user == null) {
            return Result.error("未登录");
        }
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setEmail(user.getEmail());
        vo.setNickname(user.getNickname());
        vo.setCredits(user.getCredits());
        vo.setStatus(user.getStatus());
        vo.setCreatedAt(user.getCreatedAt());
        vo.setLastLoginAt(user.getLastLoginAt());
        vo.setAvatar(user.getAvatar());
        return Result.success(vo);
    }

    /**
     * 检查昵称是否可用
     */
    @GetMapping("/user/check-nickname")
    public Result<NicknameCheckResult> checkNickname(@RequestParam String nickname) {
        Long currentUserId = UserContextHolder.getUserId();
        boolean available = userService.isNicknameAvailable(nickname, currentUserId);
        NicknameCheckResult result = new NicknameCheckResult();
        result.setAvailable(available);
        return Result.success(result);
    }

    /**
     * 昵称检查结果
     */
    @lombok.Data
    public static class NicknameCheckResult {
        private boolean available;
    }

    /**
     * 更新用户资料（昵称、头像）
     */
    @PutMapping("/user/profile")
    public Result<UserVO> updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            return Result.error("未登录");
        }

        userService.updateProfile(userId, request.getNickname(), request.getAvatar());

        // 返回更新后的用户信息
        var user = userService.getById(userId);
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setEmail(user.getEmail());
        vo.setNickname(user.getNickname());
        vo.setCredits(user.getCredits());
        vo.setStatus(user.getStatus());
        vo.setCreatedAt(user.getCreatedAt());
        vo.setLastLoginAt(user.getLastLoginAt());
        vo.setAvatar(user.getAvatar());
        return Result.success(vo);
    }

    /**
     * 退出登录
     */
    @PostMapping("/auth/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            tokenService.removeToken(token);
            log.info("用户退出登录，token已从Redis删除");
        }
        return Result.success();
    }
}