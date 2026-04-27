package com.manga.ai.episode.controller;

import com.manga.ai.common.constants.CreditConstants;
import com.manga.ai.common.enums.CreditUsageType;
import com.manga.ai.common.result.Result;
import com.manga.ai.episode.dto.EpisodeCreateRequest;
import com.manga.ai.episode.dto.EpisodeDetailVO;
import com.manga.ai.episode.dto.EpisodeProgressVO;
import com.manga.ai.episode.dto.EpisodeScriptUpdateRequest;
import com.manga.ai.episode.dto.GenerateAssetsRequest;
import com.manga.ai.episode.dto.ParsedAssetsVO;
import com.manga.ai.episode.service.EpisodeService;
import com.manga.ai.user.service.UserService;
import com.manga.ai.user.service.impl.UserServiceImpl.UserContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 剧集控制器
 */
@Slf4j
@RestController
@RequestMapping("/v1/episodes")
@RequiredArgsConstructor
public class EpisodeController {

    private final EpisodeService episodeService;
    private final UserService userService;

    /**
     * 创建剧集
     */
    @PostMapping("/series/{seriesId}")
    public Result<Long> createEpisode(@PathVariable Long seriesId, @RequestBody EpisodeCreateRequest request) {
        log.info("创建剧集: seriesId={}, episodeNumber={}", seriesId, request.getEpisodeNumber());
        Long episodeId = episodeService.createEpisode(seriesId, request);
        return Result.success(episodeId);
    }

    /**
     * 解析剧本（只解析场景和道具）
     */
    @PostMapping("/{episodeId}/parse")
    public Result<Void> parseScript(@PathVariable Long episodeId) {
        log.info("解析剧本资产: episodeId={}", episodeId);
        // 在调用异步方法前获取用户ID并扣费
        Long userId = UserContextHolder.getUserId();
        if (userId != null) {
            userService.deductCredits(userId, CreditConstants.CREDITS_PER_SCRIPT_PARSE,
                    CreditUsageType.SCRIPT_PARSE.getCode(), "剧本解析-资产解析", episodeId, "EPISODE");
            log.info("剧本解析扣费: userId={}, credits={}", userId, CreditConstants.CREDITS_PER_SCRIPT_PARSE);
        }
        episodeService.parseScript(episodeId, userId);
        return Result.success();
    }

    /**
     * 解析分镜
     */
    @PostMapping("/{episodeId}/parse-shots")
    public Result<Void> parseShots(@PathVariable Long episodeId) {
        log.info("解析分镜: episodeId={}", episodeId);
        // 在调用异步方法前获取用户ID并扣费
        Long userId = UserContextHolder.getUserId();
        if (userId != null) {
            userService.deductCredits(userId, CreditConstants.CREDITS_PER_SCRIPT_PARSE,
                    CreditUsageType.SCRIPT_PARSE.getCode(), "剧本解析-分镜解析", episodeId, "EPISODE");
            log.info("分镜解析扣费: userId={}, credits={}", userId, CreditConstants.CREDITS_PER_SCRIPT_PARSE);
        }
        episodeService.parseShots(episodeId, userId);
        return Result.success();
    }

    /**
     * 获取剧集详情
     */
    @GetMapping("/{episodeId}")
    public Result<EpisodeDetailVO> getEpisodeDetail(@PathVariable Long episodeId) {
        EpisodeDetailVO detail = episodeService.getEpisodeDetail(episodeId);
        return Result.success(detail);
    }

    /**
     * 获取剧集进度
     */
    @GetMapping("/{episodeId}/progress")
    public Result<EpisodeProgressVO> getEpisodeProgress(@PathVariable Long episodeId) {
        EpisodeProgressVO progress = episodeService.getEpisodeProgress(episodeId);
        return Result.success(progress);
    }

    /**
     * 获取系列下的所有剧集
     */
    @GetMapping("/series/{seriesId}")
    public Result<List<EpisodeDetailVO>> getEpisodesBySeriesId(@PathVariable Long seriesId) {
        List<EpisodeDetailVO> episodes = episodeService.getEpisodesBySeriesId(seriesId);
        return Result.success(episodes);
    }

    /**
     * 删除剧集
     */
    @DeleteMapping("/{episodeId}")
    public Result<Void> deleteEpisode(@PathVariable Long episodeId) {
        log.info("删除剧集: episodeId={}", episodeId);
        episodeService.deleteEpisode(episodeId);
        return Result.success();
    }

    /**
     * 更新剧本内容
     */
    @PutMapping("/{episodeId}/script")
    public Result<Void> updateScript(@PathVariable Long episodeId, @RequestBody EpisodeScriptUpdateRequest request) {
        log.info("更新剧本: episodeId={}", episodeId);
        episodeService.updateScript(episodeId, request.getScriptText());
        return Result.success();
    }

    /**
     * 获取解析后的资产清单
     * 用于让用户选择需要生成图片的资产
     */
    @GetMapping("/{episodeId}/parsed-assets")
    public Result<ParsedAssetsVO> getParsedAssets(@PathVariable Long episodeId) {
        log.info("获取解析后的资产清单: episodeId={}", episodeId);
        ParsedAssetsVO assets = episodeService.getParsedAssets(episodeId);
        return Result.success(assets);
    }

    /**
     * 批量生成选中资产的图片
     */
    @PostMapping("/{episodeId}/generate-assets")
    public Result<Void> generateSelectedAssets(
            @PathVariable Long episodeId,
            @RequestBody GenerateAssetsRequest request) {
        log.info("批量生成资产: episodeId={}, sceneIds={}, propIds={}",
                episodeId, request.getSceneIds(), request.getPropIds());
        // 获取用户ID并扣除分镜解析费用
        Long userId = UserContextHolder.getUserId();
        if (userId != null) {
            userService.deductCredits(userId, CreditConstants.CREDITS_PER_SCRIPT_PARSE,
                    CreditUsageType.SCRIPT_PARSE.getCode(), "剧本解析-分镜解析", episodeId, "EPISODE");
            log.info("分镜解析扣费: userId={}, credits={}", userId, CreditConstants.CREDITS_PER_SCRIPT_PARSE);
        }
        episodeService.generateSelectedAssets(episodeId, request, userId);
        return Result.success();
    }
}
