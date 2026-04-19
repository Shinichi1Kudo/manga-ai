package com.manga.ai.series.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 系列处理进度消息
 * 通过 WebSocket 推送给前端
 */
@Data
public class SeriesProgressMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 系列ID
     */
    private Long seriesId;

    /**
     * 系列名称
     */
    private String seriesName;

    /**
     * 状态: PROCESSING, COMPLETED, FAILED
     */
    private String status;

    /**
     * 总角色数
     */
    private Integer totalRoles;

    /**
     * 已完成角色数
     */
    private Integer completedRoles;

    /**
     * 当前正在处理的角色名称
     */
    private String currentRoleName;

    /**
     * 进度消息
     */
    private String message;

    /**
     * 进度百分比 (0-100)
     */
    private Integer progressPercent;

    /**
     * 创建处理中消息
     */
    public static SeriesProgressMessage processing(Long seriesId, int completed, int total, int percent, String message) {
        SeriesProgressMessage msg = new SeriesProgressMessage();
        msg.setSeriesId(seriesId);
        msg.setStatus("PROCESSING");
        msg.setCompletedRoles(completed);
        msg.setTotalRoles(total);
        msg.setProgressPercent(percent);
        msg.setMessage(message);
        return msg;
    }

    /**
     * 创建完成消息
     */
    public static SeriesProgressMessage completed(Long seriesId, int total) {
        SeriesProgressMessage msg = new SeriesProgressMessage();
        msg.setSeriesId(seriesId);
        msg.setStatus("COMPLETED");
        msg.setCompletedRoles(total);
        msg.setTotalRoles(total);
        msg.setProgressPercent(100);
        msg.setMessage("全部完成！");
        return msg;
    }

    /**
     * 创建失败消息
     */
    public static SeriesProgressMessage failed(Long seriesId, String errorMessage) {
        SeriesProgressMessage msg = new SeriesProgressMessage();
        msg.setSeriesId(seriesId);
        msg.setStatus("FAILED");
        msg.setMessage("处理失败: " + errorMessage);
        return msg;
    }
}
