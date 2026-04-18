package com.manga.ai.series.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 系列进度 VO
 */
@Data
public class SeriesProgressVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long seriesId;
    private String seriesName;
    private Integer seriesStatus;
    private String seriesStatusDesc;

    /**
     * 总进度百分比
     */
    private Integer progressPercent;

    /**
     * 角色数量
     */
    private Integer totalRoles;

    /**
     * 已确认角色数量
     */
    private Integer confirmedRoles;

    /**
     * 资产数量
     */
    private Integer totalAssets;

    /**
     * 已确认资产数量
     */
    private Integer confirmedAssets;

    /**
     * 待处理任务数
     */
    private Integer pendingTasks;

    /**
     * 处理中任务数
     */
    private Integer runningTasks;

    /**
     * 失败任务数
     */
    private Integer failedTasks;

    /**
     * 任务详情
     */
    private List<TaskProgressVO> tasks;

    @Data
    public static class TaskProgressVO implements Serializable {
        private Long taskId;
        private String taskType;
        private String taskTypeDesc;
        private Integer status;
        private String statusDesc;
        private String refEntityType;
        private Long refEntityId;
        private Integer retryCount;
        private String errorMessage;
    }
}
