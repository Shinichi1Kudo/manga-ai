package com.manga.ai.user.controller;

import com.manga.ai.common.dto.Result;
import com.manga.ai.user.dto.*;
import com.manga.ai.user.service.UserService;
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
        return Result.success(vo);
    }

    /**
     * 退出登录（前端清除 Token 即可）
     */
    @PostMapping("/auth/logout")
    public Result<Void> logout() {
        return Result.success();
    }
}
