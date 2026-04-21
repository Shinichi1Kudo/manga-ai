package com.manga.ai.episode.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 创建剧集请求
 */
@Data
public class EpisodeCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 集数编号
     */
    private Integer episodeNumber;

    /**
     * 剧集名称
     */
    private String episodeName;

    /**
     * 剧本文本
     */
    private String scriptText;
}
