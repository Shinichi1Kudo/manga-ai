package com.manga.ai.episode.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 剧集详情VO
 */
@Data
public class EpisodeDetailVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long seriesId;
    private String seriesName;
    private Integer episodeNumber;
    private String episodeName;
    private String scriptText;
    private String parsedScript;
    private Integer totalShots;
    private Integer totalDuration;
    private Integer status;
    private String statusDesc;
    private String errorMessage;  // 错误信息
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 分镜列表
     */
    private List<ShotSummary> shots;

    /**
     * 分镜摘要
     */
    @Data
    public static class ShotSummary implements Serializable {
        private Long id;
        private Integer shotNumber;
        private String description;
        private Integer duration;
        private String videoUrl;
        private String thumbnailUrl;
        private Integer generationStatus;
        private String generationStatusDesc;
        private Integer status;
        private String statusDesc;
    }

    public String getStatusDesc() {
        if (status == null) return "";
        switch (status) {
            case 0: return "待解析";
            case 1: return "解析中";
            case 2: return "待审核";
            case 3: return "制作中";
            case 4: return "已完成";
            default: return "";
        }
    }
}
