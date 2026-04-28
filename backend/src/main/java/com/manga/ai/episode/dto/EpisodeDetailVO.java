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
     * 角色列表
     */
    private List<RoleInfo> roles;

    /**
     * 分镜摘要
     */
    @Data
    public static class ShotSummary implements Serializable {
        private Long id;
        private Integer shotNumber;
        private Long sceneId;
        private String sceneName;
        private String description;
        private Integer duration;
        private String videoUrl;
        private String thumbnailUrl;
        private Integer generationStatus;
        private String generationStatusDesc;
        private Integer status;
        private String statusDesc;
        private List<CharacterInfo> characters;
        private List<PropInfo> props;

        /**
         * 分镜角色信息
         */
        @Data
        public static class CharacterInfo implements Serializable {
            private Long roleId;
            private String roleName;
            private Integer clothingId;
            private String clothingName;
            private String assetUrl;
            private String action;
            private String expression;
        }

        /**
         * 分镜道具信息
         */
        @Data
        public static class PropInfo implements Serializable {
            private Long propId;
            private String propName;
            private String assetUrl;
        }
    }

    /**
     * 角色信息
     */
    @Data
    public static class RoleInfo implements Serializable {
        private Long id;
        private String roleName;
        private String assetUrl;
        private Integer status;
        private String statusDesc;
        private List<ClothingInfo> clothings;

        @Data
        public static class ClothingInfo implements Serializable {
            private Long id;
            private String clothingName;
            private String assetUrl;
            private Integer status;
        }
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
