package com.manga.ai.scene.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 场景详情VO
 */
@Data
public class SceneDetailVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long seriesId;
    private String sceneName;
    private String sceneCode;
    private String description;
    private String locationType;
    private String timeOfDay;
    private String weather;
    private String customPrompt;
    private String styleKeywords;
    private Integer status;
    private String statusDesc;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 场景资产列表
     */
    private List<SceneAssetVO> assets;

    /**
     * 当前激活的资产URL（便捷字段）
     */
    private String activeAssetUrl;

    /**
     * 场景资产VO
     */
    @Data
    public static class SceneAssetVO implements Serializable {
        private Long id;
        private String assetType;
        private String viewType;
        private Integer version;
        private String filePath;
        private String thumbnailPath;
        private Integer status;
        private Integer isActive;
        private LocalDateTime createdAt;
    }

    public String getStatusDesc() {
        if (status == null) return "";
        switch (status) {
            case 0: return "生成中";
            case 1: return "待审核";
            case 3: return "已锁定";
            default: return "";
        }
    }

    /**
     * 是否已锁定（便捷字段）
     */
    @JsonProperty("isLocked")
    public Boolean getIsLocked() {
        return status != null && status == 3;
    }
}
