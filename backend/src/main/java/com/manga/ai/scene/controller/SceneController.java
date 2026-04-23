package com.manga.ai.scene.controller;

import com.manga.ai.common.result.Result;
import com.manga.ai.scene.dto.SceneDetailVO;
import com.manga.ai.scene.service.SceneService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 场景控制器
 */
@Slf4j
@RestController
@RequestMapping("/v1/scenes")
@RequiredArgsConstructor
public class SceneController {

    private final SceneService sceneService;

    /**
     * 获取系列下所有场景
     */
    @GetMapping("/series/{seriesId}")
    public Result<List<SceneDetailVO>> getScenesBySeriesId(@PathVariable Long seriesId) {
        List<SceneDetailVO> scenes = sceneService.getScenesBySeriesId(seriesId);
        return Result.success(scenes);
    }

    /**
     * 获取场景详情
     */
    @GetMapping("/{sceneId}")
    public Result<SceneDetailVO> getSceneDetail(@PathVariable Long sceneId) {
        SceneDetailVO detail = sceneService.getSceneDetail(sceneId);
        return Result.success(detail);
    }

    /**
     * 手动创建场景
     */
    @PostMapping
    public Result<Long> createScene(@RequestBody Map<String, Object> body) {
        Long seriesId = Long.valueOf(body.get("seriesId").toString());
        Long episodeId = body.get("episodeId") != null ? Long.valueOf(body.get("episodeId").toString()) : null;
        String sceneName = (String) body.get("sceneName");
        String aspectRatio = (String) body.get("aspectRatio");
        String quality = (String) body.get("quality");
        String customPrompt = (String) body.get("customPrompt");

        log.info("手动创建场景: seriesId={}, episodeId={}, sceneName={}, aspectRatio={}, quality={}, customPrompt={}",
                seriesId, episodeId, sceneName, aspectRatio, quality, customPrompt != null);

        Long sceneId = sceneService.createScene(seriesId, episodeId, sceneName, aspectRatio, quality, customPrompt);
        return Result.success(sceneId);
    }

    /**
     * 生成场景资产
     */
    @PostMapping("/{sceneId}/generate")
    public Result<Void> generateSceneAssets(@PathVariable Long sceneId) {
        log.info("生成场景资产: sceneId={}", sceneId);
        sceneService.generateSceneAssets(sceneId);
        return Result.success();
    }

    /**
     * 重新生成场景图片
     */
    @PostMapping("/{sceneId}/regenerate")
    public Result<Void> regenerateSceneAsset(
            @PathVariable Long sceneId,
            @RequestBody(required = false) Map<String, Object> body) {
        String customPrompt = body != null ? (String) body.get("customPrompt") : null;
        String aspectRatio = body != null ? (String) body.get("aspectRatio") : null;
        String quality = body != null ? (String) body.get("quality") : null;
        log.info("重新生成场景资产: sceneId={}, aspectRatio={}, quality={}, customPrompt={}",
                sceneId, aspectRatio, quality, customPrompt != null);
        sceneService.regenerateSceneAsset(sceneId, customPrompt, aspectRatio, quality);
        return Result.success();
    }

    /**
     * 审核场景
     */
    @PostMapping("/{sceneId}/review")
    public Result<Void> reviewScene(
            @PathVariable Long sceneId,
            @RequestBody Map<String, Boolean> body) {
        boolean approved = body.getOrDefault("approved", true);
        log.info("审核场景: sceneId={}, approved={}", sceneId, approved);
        sceneService.reviewScene(sceneId, approved);
        return Result.success();
    }

    /**
     * 锁定场景
     */
    @PostMapping("/{sceneId}/lock")
    public Result<Void> lockScene(@PathVariable Long sceneId) {
        log.info("锁定场景: sceneId={}", sceneId);
        sceneService.lockScene(sceneId);
        return Result.success();
    }

    /**
     * 解锁场景
     */
    @PostMapping("/{sceneId}/unlock")
    public Result<Void> unlockScene(@PathVariable Long sceneId) {
        log.info("解锁场景: sceneId={}", sceneId);
        sceneService.unlockScene(sceneId);
        return Result.success();
    }

    /**
     * 更新场景名称
     */
    @PutMapping("/{sceneId}/name")
    public Result<Void> updateSceneName(
            @PathVariable Long sceneId,
            @RequestBody Map<String, String> body) {
        String sceneName = body.get("sceneName");
        log.info("更新场景名称: sceneId={}, sceneName={}", sceneId, sceneName);
        sceneService.updateSceneName(sceneId, sceneName);
        return Result.success();
    }

    /**
     * 删除场景
     */
    @DeleteMapping("/{sceneId}")
    public Result<Void> deleteScene(@PathVariable Long sceneId) {
        log.info("删除场景: sceneId={}", sceneId);
        sceneService.deleteScene(sceneId);
        return Result.success();
    }

    /**
     * 回滚到指定版本
     */
    @PostMapping("/{sceneId}/rollback")
    public Result<Void> rollbackToVersion(
            @PathVariable Long sceneId,
            @RequestBody Map<String, Long> body) {
        Long assetId = body.get("assetId");
        log.info("回滚场景版本: sceneId={}, assetId={}", sceneId, assetId);
        sceneService.rollbackToVersion(sceneId, assetId);
        return Result.success();
    }

    /**
     * 重置卡在生成中状态的场景
     */
    @PostMapping("/{sceneId}/reset-status")
    public Result<Void> resetSceneStatus(@PathVariable Long sceneId) {
        log.info("重置场景状态: sceneId={}", sceneId);
        sceneService.resetStuckStatus(sceneId);
        return Result.success();
    }
}
