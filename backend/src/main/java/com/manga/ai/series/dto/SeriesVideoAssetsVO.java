package com.manga.ai.series.dto;

import lombok.Data;

import java.util.List;

/**
 * 系列视频资产VO
 */
@Data
public class SeriesVideoAssetsVO {

    private Long seriesId;

    private String seriesName;

    private List<EpisodeVideoAssetsVO> episodes;
}
