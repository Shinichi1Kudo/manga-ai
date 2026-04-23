package com.manga.ai.episode.service;

import com.manga.ai.episode.dto.EpisodeCreateRequest;
import com.manga.ai.episode.dto.EpisodeDetailVO;
import com.manga.ai.episode.dto.EpisodeProgressVO;
import com.manga.ai.episode.dto.GenerateAssetsRequest;
import com.manga.ai.episode.dto.ParsedAssetsVO;
import com.manga.ai.episode.entity.Episode;

import java.util.List;

/**
 * 剧集服务接口
 */
public interface EpisodeService {

    /**
     * 创建剧集
     * @param seriesId 系列ID
     * @param request 创建请求
     * @return 剧集ID
     */
    Long createEpisode(Long seriesId, EpisodeCreateRequest request);

    /**
     * 异步解析剧本（只解析场景和道具）
     * @param episodeId 剧集ID
     */
    void parseScript(Long episodeId);

    /**
     * 异步解析分镜
     * @param episodeId 剧集ID
     */
    void parseShots(Long episodeId);

    /**
     * 获取剧集详情
     * @param episodeId 剧集ID
     * @return 剧集详情
     */
    EpisodeDetailVO getEpisodeDetail(Long episodeId);

    /**
     * 获取剧集进度
     * @param episodeId 剧集ID
     * @return 进度信息
     */
    EpisodeProgressVO getEpisodeProgress(Long episodeId);

    /**
     * 获取系列下的所有剧集
     * @param seriesId 系列ID
     * @return 剧集列表
     */
    List<EpisodeDetailVO> getEpisodesBySeriesId(Long seriesId);

    /**
     * 删除剧集
     * @param episodeId 剧集ID
     */
    void deleteEpisode(Long episodeId);

    /**
     * 更新剧本内容
     * @param episodeId 剧集ID
     * @param scriptText 剧本文本
     */
    void updateScript(Long episodeId, String scriptText);

    /**
     * 获取解析后的资产清单
     * @param episodeId 剧集ID
     * @return 资产清单（包含场景和道具）
     */
    ParsedAssetsVO getParsedAssets(Long episodeId);

    /**
     * 批量生成选中资产的图片
     * @param episodeId 剧集ID
     * @param request 生成请求（包含选中的场景和道具ID）
     */
    void generateSelectedAssets(Long episodeId, GenerateAssetsRequest request);
}
