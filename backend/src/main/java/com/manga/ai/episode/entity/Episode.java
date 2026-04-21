package com.manga.ai.episode.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 剧集实体
 */
@Data
@TableName("episode")
public class Episode implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 系列ID
     */
    private Long seriesId;

    /**
     * 集数编号
     */
    private Integer episodeNumber;

    /**
     * 剧集名称
     */
    private String episodeName;

    /**
     * 原始剧本文本
     */
    private String scriptText;

    /**
     * 解析后的剧本JSON
     */
    private String parsedScript;

    /**
     * 总分镜数
     */
    private Integer totalShots;

    /**
     * 总时长(秒)
     */
    private Integer totalDuration;

    /**
     * 状态: 0-待解析 1-解析中 2-待审核 3-制作中 4-已完成
     */
    private Integer status;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 是否删除
     */
    @TableLogic
    private Integer isDeleted;
}
