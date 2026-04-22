package com.manga.ai.prop.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 道具详情VO
 */
@Data
public class PropDetailVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long seriesId;
    private String propName;
    private String propCode;
    private String description;
    private String propType;
    private String color;
    private String size;
    private String customPrompt;
    private String styleKeywords;
    private Integer status;
    private String statusDesc;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 道具资产列表
     */
    private List<PropAssetVO> assets;

    /**
     * 当前激活的资产URL（便捷字段）
     */
    private String activeAssetUrl;

    /**
     * 透明背景PNG URL
     */
    private String transparentUrl;

    /**
     * 道具资产VO
     */
    @Data
    public static class PropAssetVO implements Serializable {
        private Long id;
        private String assetType;
        private String viewType;
        private Integer version;
        private String filePath;
        private String transparentPath;
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
