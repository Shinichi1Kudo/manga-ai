package com.manga.ai.shot.service;

import com.manga.ai.shot.dto.ShotDetailVO;
import com.manga.ai.shot.dto.ShotReviewRequest;
import com.manga.ai.shot.dto.ShotUpdateRequest;

import java.util.List;

/**
 * 分镜服务接口
 */
public interface ShotService {

    /**
     * 获取分镜详情
     * @param shotId 分镜ID
     * @return 分镜详情
     */
    ShotDetailVO getShotDetail(Long shotId);

    /**
     * 获取剧集下的所有分镜
     * @param episodeId 剧集ID
     * @return 分镜列表
     */
    List<ShotDetailVO> getShotsByEpisodeId(Long episodeId);

    /**
     * 更新分镜
     * @param shotId 分镜ID
     * @param request 更新请求
     */
    void updateShot(Long shotId, ShotUpdateRequest request);

    /**
     * 审核分镜
     * @param shotId 分镜ID
     * @param request 审核请求
     */
    void reviewShot(Long shotId, ShotReviewRequest request);

    /**
     * 异步生成视频
     * @param shotId 分镜ID
     */
    void generateVideo(Long shotId);

    /**
     * 批量生成视频
     * @param episodeId 剧集ID
     */
    void generateVideosForEpisode(Long episodeId);
}
