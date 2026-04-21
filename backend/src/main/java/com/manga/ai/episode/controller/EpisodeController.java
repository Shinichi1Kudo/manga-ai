package com.manga.ai.episode.controller;

import com.manga.ai.common.result.Result;
import com.manga.ai.episode.dto.EpisodeCreateRequest;
import com.manga.ai.episode.dto.EpisodeDetailVO;
import com.manga.ai.episode.dto.EpisodeProgressVO;
import com.manga.ai.episode.service.EpisodeService;
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
     * 解析剧本
     */
    @PostMapping("/{episodeId}/parse")
    public Result<Void> parseScript(@PathVariable Long episodeId) {
        log.info("解析剧本: episodeId={}", episodeId);
        episodeService.parseScript(episodeId);
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
}
