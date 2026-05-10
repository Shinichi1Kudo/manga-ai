package com.manga.ai.episode.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 剧集进度VO
 */
@Data
public class EpisodeProgressVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long episodeId;
    private Integer status;
    private String statusDesc;
    private Integer totalShots;
    private Integer completedShots;
    private Integer failedShots;
    private Integer progress;  // 百分比
    private Boolean assetsReady;  // 资产解析完成，等待用户选择
    private Boolean assetsConfirmed;  // 用户已确认资产选择
    private Boolean shotsParsing;  // 正在解析分镜（资产已确认）
    private Boolean shotParseFailed;  // 分镜解析失败，可重新选择资产后重试
    private String errorMessage;  // 解析失败原因
    private List<ShotProgress> shots;  // 分镜进度列表

    /**
     * 分镜进度
     */
    @Data
    public static class ShotProgress implements Serializable {
        private Long id;
        private Integer generationStatus;
        private String generationStartTime;
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
