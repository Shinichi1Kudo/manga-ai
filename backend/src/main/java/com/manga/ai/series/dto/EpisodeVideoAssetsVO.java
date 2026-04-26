package com.manga.ai.series.dto;

import lombok.Data;

import java.util.List;

/**
 * 剧集视频资产VO
 */
@Data
public class EpisodeVideoAssetsVO {

    private Long episodeId;

    private Integer episodeNumber;

    private String episodeName;

    private Integer totalShots;

    private Integer completedShots;

    private List<ShotVideoInfoVO> shots;
}
