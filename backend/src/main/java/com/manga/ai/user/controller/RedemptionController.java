package com.manga.ai.user.controller;

import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.common.result.Result;
import com.manga.ai.user.dto.RedeemRequest;
import com.manga.ai.user.dto.RedeemResultVO;
import com.manga.ai.user.service.RedemptionService;
import com.manga.ai.user.service.impl.UserServiceImpl.UserContextHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 兑换码控制器
 */
@Slf4j
@RestController
@RequestMapping("/v1/credits")
@RequiredArgsConstructor
public class RedemptionController {

    private final RedemptionService redemptionService;

    /**
     * 兑换积分
     */
    @PostMapping("/redeem")
    public Result<RedeemResultVO> redeem(@Valid @RequestBody RedeemRequest request) {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            log.warn("兑换失败: 用户未登录");
            return Result.fail("请先登录");
        }

        log.info("兑换积分: userId={}, code={}", userId, request.getCode());

        try {
            RedeemResultVO result = redemptionService.redeem(userId, request.getCode());
            return Result.success(result);
        } catch (BusinessException e) {
            log.warn("兑换失败: userId={}, error={}", userId, e.getMessage());
            return Result.fail(e.getMessage());
        }
    }
}
