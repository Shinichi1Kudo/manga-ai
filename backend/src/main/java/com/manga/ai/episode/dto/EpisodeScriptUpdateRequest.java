package com.manga.ai.episode.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 更新剧本请求
 */
@Data
public class EpisodeScriptUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 剧本文本
     */
    private String scriptText;
}
