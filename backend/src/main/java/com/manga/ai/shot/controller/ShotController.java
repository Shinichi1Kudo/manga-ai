package com.manga.ai.shot.controller;

import com.manga.ai.common.result.Result;
import com.manga.ai.shot.dto.ReferenceImageDTO;
import com.manga.ai.shot.dto.ShotDetailVO;
import com.manga.ai.shot.dto.ShotReferenceUpdateRequest;
import com.manga.ai.shot.dto.ShotReviewRequest;
import com.manga.ai.shot.dto.ShotUpdateRequest;
import com.manga.ai.shot.service.ShotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
     * 生成视频
     */
    @PostMapping("/{shotId}/generate")
    public Result<Void> generateVideo(@PathVariable Long shotId) {
        log.info("生成视频: shotId={}", shotId);
        shotService.generateVideo(shotId);
        return Result.success();
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
    public Result<Void> generateVideoWithReferences(@PathVariable Long shotId) {
        log.info("带参考图生成视频: shotId={}", shotId);
        shotService.generateVideoWithReferences(shotId);
        return Result.success();
    }
}
