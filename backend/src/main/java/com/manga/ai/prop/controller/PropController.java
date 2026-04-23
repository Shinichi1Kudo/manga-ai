package com.manga.ai.prop.controller;

import com.manga.ai.common.result.Result;
import com.manga.ai.prop.dto.PropDetailVO;
import com.manga.ai.prop.service.PropService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 道具控制器
 */
@Slf4j
@RestController
@RequestMapping("/v1/props")
@RequiredArgsConstructor
public class PropController {

    private final PropService propService;

    /**
     * 获取系列下所有道具
     */
    @GetMapping("/series/{seriesId}")
    public Result<List<PropDetailVO>> getPropsBySeriesId(@PathVariable Long seriesId) {
        List<PropDetailVO> props = propService.getPropsBySeriesId(seriesId);
        return Result.success(props);
    }

    /**
     * 获取道具详情
     */
    @GetMapping("/{propId}")
    public Result<PropDetailVO> getPropDetail(@PathVariable Long propId) {
        PropDetailVO detail = propService.getPropDetail(propId);
        return Result.success(detail);
    }

    /**
     * 手动创建道具
     */
    @PostMapping
    public Result<Long> createProp(@RequestBody Map<String, Object> body) {
        Long seriesId = Long.valueOf(body.get("seriesId").toString());
        Long episodeId = body.get("episodeId") != null ? Long.valueOf(body.get("episodeId").toString()) : null;
        String propName = (String) body.get("propName");
        String quality = (String) body.get("quality");
        String customPrompt = (String) body.get("customPrompt");

        log.info("手动创建道具: seriesId={}, episodeId={}, propName={}, quality={}, customPrompt={}",
                seriesId, episodeId, propName, quality, customPrompt);

        Long propId = propService.createProp(seriesId, episodeId, propName, quality, customPrompt);
        return Result.success(propId);
    }

    /**
     * 生成道具资产
     */
    @PostMapping("/{propId}/generate")
    public Result<Void> generatePropAssets(@PathVariable Long propId) {
        log.info("生成道具资产: propId={}", propId);
        propService.generatePropAssets(propId);
        return Result.success();
    }

    /**
     * 重新生成道具图片
     */
    @PostMapping("/{propId}/regenerate")
    public Result<Void> regeneratePropAsset(
            @PathVariable Long propId,
            @RequestBody(required = false) Map<String, Object> body) {
        String customPrompt = body != null ? (String) body.get("customPrompt") : null;
        String quality = body != null ? (String) body.get("quality") : null;
        log.info("重新生成道具资产: propId={}, quality={}, customPrompt={}",
                propId, quality, customPrompt != null);
        propService.regeneratePropAsset(propId, customPrompt, quality);
        return Result.success();
    }

    /**
     * 审核道具
     */
    @PostMapping("/{propId}/review")
    public Result<Void> reviewProp(
            @PathVariable Long propId,
            @RequestBody Map<String, Boolean> body) {
        boolean approved = body.getOrDefault("approved", true);
        log.info("审核道具: propId={}, approved={}", propId, approved);
        propService.reviewProp(propId, approved);
        return Result.success();
    }

    /**
     * 锁定道具
     */
    @PostMapping("/{propId}/lock")
    public Result<Void> lockProp(@PathVariable Long propId) {
        log.info("锁定道具: propId={}", propId);
        propService.lockProp(propId);
        return Result.success();
    }

    /**
     * 解锁道具
     */
    @PostMapping("/{propId}/unlock")
    public Result<Void> unlockProp(@PathVariable Long propId) {
        log.info("解锁道具: propId={}", propId);
        propService.unlockProp(propId);
        return Result.success();
    }

    /**
     * 更新道具名称
     */
    @PutMapping("/{propId}/name")
    public Result<Void> updatePropName(
            @PathVariable Long propId,
            @RequestBody Map<String, String> body) {
        String propName = body.get("propName");
        log.info("更新道具名称: propId={}, propName={}", propId, propName);
        propService.updatePropName(propId, propName);
        return Result.success();
    }

    /**
     * 删除道具
     */
    @DeleteMapping("/{propId}")
    public Result<Void> deleteProp(@PathVariable Long propId) {
        log.info("删除道具: propId={}", propId);
        propService.deleteProp(propId);
        return Result.success();
    }

    /**
     * 回滚到指定版本
     */
    @PostMapping("/{propId}/rollback")
    public Result<Void> rollbackToVersion(
            @PathVariable Long propId,
            @RequestBody Map<String, Long> body) {
        Long assetId = body.get("assetId");
        log.info("回滚道具版本: propId={}, assetId={}", propId, assetId);
        propService.rollbackToVersion(propId, assetId);
        return Result.success();
    }
}
