package com.manga.ai.user.controller;

import com.manga.ai.common.result.Result;
import com.manga.ai.user.dto.CreditAdminDashboardVO;
import com.manga.ai.user.service.CreditAdminService;
import com.manga.ai.user.service.impl.UserServiceImpl.UserContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 积分管理后台控制器
 */
@RestController
@RequestMapping("/v1/admin/credits")
@RequiredArgsConstructor
public class CreditAdminController {

    private final CreditAdminService creditAdminService;

    @GetMapping("/dashboard")
    public Result<CreditAdminDashboardVO> dashboard(
            @RequestParam(required = false) Integer hours,
            @RequestParam(required = false) Integer recordPage,
            @RequestParam(required = false) Integer recordPageSize,
            @RequestParam(required = false) String nickname) {
        Long userId = UserContextHolder.getUserId();
        return Result.success(creditAdminService.getDashboard(userId, hours, recordPage, recordPageSize, nickname));
    }
}
