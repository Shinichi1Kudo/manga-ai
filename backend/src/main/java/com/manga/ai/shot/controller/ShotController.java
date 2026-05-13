package com.manga.ai.shot.controller;

import com.manga.ai.common.result.Result;
import com.manga.ai.shot.dto.ReferenceImageDTO;
import com.manga.ai.shot.dto.ShotDetailVO;
import com.manga.ai.shot.dto.ShotReferenceUpdateRequest;
import com.manga.ai.shot.dto.ShotReviewRequest;
import com.manga.ai.shot.dto.ShotUpdateRequest;
import com.manga.ai.shot.dto.ShotVideoAssetVO;
import com.manga.ai.shot.service.ShotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分镜控制器
 */
@Slf4j
@RestController
@RequestMapping("/v1/shots")
@RequiredArgsConstructor
public class ShotController {

    private final ShotService shotService;

    /**
     * 获取分镜详情
     */
    @GetMapping("/{shotId}")
    public Result<ShotDetailVO> getShotDetail(@PathVariable Long shotId) {
        ShotDetailVO detail = shotService.getShotDetail(shotId);
        return Result.success(detail);
    }

    /**
     * 获取剧集下的所有分镜
     */
    @GetMapping("/episode/{episodeId}")
    public Result<List<ShotDetailVO>> getShotsByEpisodeId(@PathVariable Long episodeId) {
        List<ShotDetailVO> shots = shotService.getShotsByEpisodeId(episodeId);
        return Result.success(shots);
    }

    /**
     * 更新分镜
     */
    @PutMapping("/{shotId}")
    public Result<Void> updateShot(@PathVariable Long shotId, @RequestBody ShotUpdateRequest request) {
        log.info("更新分镜: shotId={}", shotId);
        shotService.updateShot(shotId, request);
        return Result.success();
    }

    /**
     * 审核分镜
     */
    @PostMapping("/{shotId}/review")
    public Result<Void> reviewShot(@PathVariable Long shotId, @RequestBody ShotReviewRequest request) {
        log.info("审核分镜: shotId={}, approved={}", shotId, request.getApproved());
        shotService.reviewShot(shotId, request);
        return Result.success();
    }

    /**
     * 解锁分镜
     */
    @PostMapping("/{shotId}/unlock")
    public Result<Void> unlockShot(@PathVariable Long shotId) {
        log.info("解锁分镜: shotId={}", shotId);
        shotService.unlockShot(shotId);
        return Result.success();
    }

    /**
     * 生成视频
     */
    @PostMapping("/{shotId}/generate")
    public Result<Void> generateVideo(@PathVariable Long shotId) {
        log.info("生成视频: shotId={}", shotId);
        shotService.generateVideo(shotId);
        return Result.success();
    }

    /**
     * 手动上传视频
     */
    @PostMapping("/{shotId}/upload-video")
    public Result<ShotDetailVO> uploadVideo(
            @PathVariable Long shotId,
            @RequestParam("aspectRatio") String aspectRatio,
            @RequestParam("file") MultipartFile file) {
        log.info("手动上传分镜视频: shotId={}, aspectRatio={}", shotId, aspectRatio);
        ShotDetailVO detail = shotService.uploadVideo(shotId, aspectRatio, file);
        return Result.success(detail);
    }

    /**
     * 获取视频生成积分预览
     */
    @GetMapping("/{shotId}/credit-preview")
    public Result<Map<String, Object>> getVideoCreditPreview(@PathVariable Long shotId) {
        log.info("获取积分预览: shotId={}", shotId);
        Map<String, Object> preview = shotService.getVideoCreditPreview(shotId);
        return Result.success(preview);
    }

    /**
     * 批量生成视频
     */
    @PostMapping("/episode/{episodeId}/generate")
    public Result<Void> generateVideosForEpisode(@PathVariable Long episodeId) {
        log.info("批量生成视频: episodeId={}", episodeId);
        shotService.generateVideosForEpisode(episodeId);
        return Result.success();
    }

    /**
     * 获取分镜参考图列表
     */
    @GetMapping("/{shotId}/references")
    public Result<List<ReferenceImageDTO>> getReferenceImages(@PathVariable Long shotId) {
        List<ReferenceImageDTO> references = shotService.getReferenceImages(shotId);
        return Result.success(references);
    }

    /**
     * 更新分镜参考图列表
     */
    @PutMapping("/{shotId}/references")
    public Result<Void> updateReferenceImages(
            @PathVariable Long shotId,
            @RequestBody ShotReferenceUpdateRequest request) {
        log.info("更新分镜参考图: shotId={}, count={}", shotId, request.getReferenceImages() != null ? request.getReferenceImages().size() : 0);
        shotService.updateReferenceImages(shotId, request.getReferenceImages());
        return Result.success();
    }

    /**
     * 自动匹配分镜文案中的资产
     */
    @PostMapping("/{shotId}/match-assets")
    public Result<List<ReferenceImageDTO>> matchAssets(@PathVariable Long shotId) {
        log.info("自动匹配分镜资产: shotId={}", shotId);
        List<ReferenceImageDTO> matched = shotService.matchAssetsFromDescription(shotId);
        return Result.success(matched);
    }

    /**
     * 带参考图生成视频
     */
    @PostMapping("/{shotId}/generate-with-references")
    public Result<Void> generateVideoWithReferences(@PathVariable Long shotId, @RequestBody(required = false) java.util.Map<String, Object> body) {
        java.util.List<String> referenceUrls = null;
        if (body != null && body.get("referenceUrls") != null) {
            referenceUrls = (java.util.List<String>) body.get("referenceUrls");
        }
        log.info("带参考图生成视频: shotId={}, referenceUrls={}", shotId, referenceUrls);
        shotService.generateVideoWithReferences(shotId, referenceUrls);
        return Result.success();
    }

    /**
     * 获取分镜视频版本历史
     */
    @GetMapping("/{shotId}/video-history")
    public Result<List<ShotVideoAssetVO>> getVideoHistory(@PathVariable Long shotId) {
        List<ShotVideoAssetVO> history = shotService.getVideoHistory(shotId);
        return Result.success(history);
    }

    /**
     * 回滚到指定视频版本
     */
    @PostMapping("/{shotId}/rollback-video/{assetId}")
    public Result<ShotDetailVO> rollbackVideo(@PathVariable Long shotId, @PathVariable Long assetId) {
        log.info("回滚视频版本: shotId={}, assetId={}", shotId, assetId);
        ShotDetailVO shot = shotService.rollbackToVersion(shotId, assetId);
        return Result.success(shot);
    }

    /**
     * 创建分镜
     */
    @PostMapping("/episode/{episodeId}/create")
    public Result<ShotDetailVO> createShot(
            @PathVariable Long episodeId,
            @RequestBody(required = false) ShotUpdateRequest request) {
        log.info("创建分镜: episodeId={}", episodeId);
        ShotDetailVO shot = shotService.createShot(episodeId, request);
        return Result.success(shot);
    }

    /**
     * 删除分镜
     */
    @DeleteMapping("/{shotId}")
    public Result<Void> deleteShot(@PathVariable Long shotId) {
        log.info("删除分镜: shotId={}", shotId);
        shotService.deleteShot(shotId);
        return Result.success();
    }

    /**
     * 重新排序分镜
     */
    @PostMapping("/episode/{episodeId}/reorder")
    public Result<Void> reorderShots(
            @PathVariable Long episodeId,
            @RequestBody java.util.Map<String, Object> body) {
        List<?> rawIds = (List<?>) body.get("shotIds");
        List<Long> shotIds = rawIds.stream()
                .map(id -> {
                    if (id instanceof Number) {
                        return ((Number) id).longValue();
                    }
                    return Long.parseLong(id.toString());
                })
                .collect(java.util.stream.Collectors.toList());
        Integer reviewStatus = null;
        Object rawReviewStatus = body.get("reviewStatus");
        if (rawReviewStatus instanceof Number) {
            reviewStatus = ((Number) rawReviewStatus).intValue();
        } else if (rawReviewStatus != null && !rawReviewStatus.toString().isBlank()) {
            reviewStatus = Integer.parseInt(rawReviewStatus.toString());
        }
        log.info("重新排序分镜: episodeId={}, reviewStatus={}, shotIds={}", episodeId, reviewStatus, shotIds);
        shotService.reorderShots(episodeId, shotIds, reviewStatus);
        return Result.success();
    }
}
