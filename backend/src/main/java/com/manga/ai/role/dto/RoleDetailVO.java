package com.manga.ai.role.dto;

import com.manga.ai.common.enums.RoleStatus;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 角色详情 VO
 */
@Data
public class RoleDetailVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long seriesId;
    private String seriesName;
    private String roleName;
    private String roleCode;
    private Integer status;
    private String statusDesc;
    private String age;
    private String gender;
    private String appearance;
    private String personality;
    private String clothing;
    private String specialMarks;
    private String customPrompt;
    private String originalPrompt;
    private String styleKeywords;
    private String originalText;
    private BigDecimal extractConfidence;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 扩展属性
     */
    private Map<String, String> attributes;

    /**
     * 资产列表
     */
    private List<AssetInfo> assets;

    public String getStatusDesc() {
        RoleStatus roleStatus = RoleStatus.getByCode(this.status);
        return roleStatus != null ? roleStatus.getDesc() : "";
    }

    @Data
    public static class AssetInfo implements Serializable {
        private Long id;
        private String assetType;
        private String viewType;
        private String viewName;
        private Integer clothingId;
        private Integer version;
        private String filePath;
        private String transparentPath;
        private String thumbnailPath;
        private Integer status;
        private Boolean validationPassed;
    }
}
