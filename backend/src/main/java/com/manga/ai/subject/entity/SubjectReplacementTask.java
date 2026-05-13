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

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
