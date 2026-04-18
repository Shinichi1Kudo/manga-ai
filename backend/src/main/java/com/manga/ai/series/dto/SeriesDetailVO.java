package com.manga.ai.series.dto;

import com.manga.ai.common.enums.SeriesStatus;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 系列详情 VO
 */
@Data
public class SeriesDetailVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String seriesName;
    private String outline;
    private String background;
    private String characterIntro;
    private String styleKeywords;
    private String colorPreference;
    private String artStyleRef;
    private Integer status;
    private String statusDesc;
    private String projectPath;
    private Long globalSeed;
    private String globalStylePrompt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;

    /**
     * 角色数量
     */
    private Integer roleCount;

    /**
     * 已确认角色数量
     */
    private Integer confirmedRoleCount;

    /**
     * 资产数量
     */
    private Integer assetCount;

    /**
     * 已确认资产数量
     */
    private Integer confirmedAssetCount;

    public String getStatusDesc() {
        SeriesStatus seriesStatus = SeriesStatus.getByCode(this.status);
        return seriesStatus != null ? seriesStatus.getDesc() : "";
    }
}
