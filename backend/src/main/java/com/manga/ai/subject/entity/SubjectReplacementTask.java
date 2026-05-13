package com.manga.ai.subject.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 主体替换任务
 */
@Data
@TableName("subject_replacement_task")
public class SubjectReplacementTask implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String taskName;

    private String originalVideoUrl;

    private String outputVideoUrl;

    private String thumbnailUrl;

    /**
     * pending / running / succeeded / failed
     */
    private String status;

    private String aspectRatio;

    private Integer duration;

    private Boolean generateAudio;

    private Boolean watermark;

    private String model;

    private String prompt;

    private String replacementsJson;

    private String volcengineTaskId;

    private String errorMessage;

    private LocalDateTime submittedAt;

    private LocalDateTime completedAt;

    private Integer generationDuration;

    private Long seed;

    /**
     * 已扣除的积分（用于生成失败时返还）
     */
    private Integer deductedCredits;

    /**
     * 积分是否已返还，避免重复返还
     */
    private Boolean creditsRefunded;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
