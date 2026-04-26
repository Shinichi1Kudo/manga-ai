package com.manga.ai.user.controller;

import com.manga.ai.common.result.PageResult;
import com.manga.ai.common.result.Result;
import com.manga.ai.user.dto.CreditRecordVO;
import com.manga.ai.user.service.CreditRecordService;
import com.manga.ai.user.service.impl.UserServiceImpl.UserContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 积分记录控制器
 */
@Slf4j
@RestController
@RequestMapping("/v1/credits")
@RequiredArgsConstructor
public class CreditRecordController {

    private final CreditRecordService creditRecordService;

    /**
     * 获取积分记录列表(分页)
     */
    @GetMapping("/records")
    public Result<PageResult<CreditRecordVO>> getCreditRecords(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String type) {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            log.warn("获取积分记录失败: 用户未登录");
            return Result.fail("请先登录");
        }
        log.info("获取积分记录: userId={}, page={}, pageSize={}, type={}", userId, page, pageSize, type);
        PageResult<CreditRecordVO> result = creditRecordService.getCreditRecords(userId, page, pageSize, type);
        return Result.success(result);
    }

    /**
     * 获取最近的积分记录
     */
    @GetMapping("/records/recent")
    public Result<List<CreditRecordVO>> getRecentRecords(
            @RequestParam(defaultValue = "10") Integer limit) {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            log.warn("获取最近积分记录失败: 用户未登录");
            return Result.fail("请先登录");
        }
        log.info("获取最近积分记录: userId={}, limit={}", userId, limit);
        List<CreditRecordVO> records = creditRecordService.getRecentRecords(userId, limit);
        return Result.success(records);
    }
}
