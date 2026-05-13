package com.manga.ai.shot.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 分镜详情VO
 */
@Data
public class ShotDetailVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long episodeId;
    private Integer shotNumber;
    private String shotName;
    private Long sceneId;
    private String sceneName;
    private String sceneAssetUrl;  // 场景资产缩略图URL（实时查询）
    private String description;
    private String cameraAngle;
    private String cameraMovement;
    private String shotType;
    private Integer startTime;
    private Integer endTime;
    private Integer duration;
    private String resolution;
    private String aspectRatio;
    private String soundEffect;
    private String charactersJson;
    private String propsJson;
    private String referencePrompt;
    private String userPrompt;
    private String videoUrl;
    private String thumbnailUrl;
    private Integer generationStatus;
    private String generationStatusDesc;
    private String generationError;
    private Integer generationDuration;
    private LocalDateTime generationStartTime;
    private Integer status;
    private String statusDesc;
    private String videoModel;
    private String videoSource;  // manual-手动上传，system-系统生成
    private Boolean descriptionEdited;
    private Boolean sceneEdited;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 角色信息
     */
    private List<CharacterInfo> characters;

    /**
     * 道具信息
     */
    private List<PropInfo> props;

    /**
     * 角色信息
     */
    @Data
    public static class CharacterInfo implements Serializable {
        private Long roleId;
        private String roleName;
        private String action;
        private String expression;
        private Integer clothingId;
        private String clothingName;
        private String assetUrl;  // 角色资产图片URL
        private BigDecimal positionX;
        private BigDecimal positionY;
        private BigDecimal scale;
    }

    /**
     * 道具信息
     */
    @Data
    public static class PropInfo implements Serializable {
        private Long propId;
        private String propName;
        private String assetUrl;  // 道具资产图片URL
        private BigDecimal positionX;
        private BigDecimal positionY;
        private BigDecimal scale;
        private BigDecimal rotation;
    }

    public String getGenerationStatusDesc() {
        if (generationStatus == null) return "";
        switch (generationStatus) {
            case 0: return "待生成";
            case 1: return "生成中";
            case 2: return "已生成";
            case 3: return "生成失败";
            default: return "";
        }
    }

    public String getStatusDesc() {
        if (status == null) return "";
        switch (status) {
            case 0: return "待审核";
            case 1: return "已通过";
            case 2: return "已拒绝";
            default: return "";
        }
    }
}
