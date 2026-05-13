package com.manga.ai.subject.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 主体替换任务展示对象
 */
@Data
public class SubjectReplacementTaskVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String taskName;

    private String originalVideoUrl;

    private String outputVideoUrl;

    private String thumbnailUrl;

    private String status;

    private String statusDesc;

    private Integer progressPercent;

    private String aspectRatio;

    private Integer duration;

    private Boolean generateAudio;

    private Boolean watermark;

    private String model;

    private String prompt;

    private List<SubjectReplacementItemDTO> replacements;

    private String volcengineTaskId;

    private String errorMessage;

    private Integer generationDuration;

    private LocalDateTime createdAt;

    private LocalDateTime submittedAt;

    private LocalDateTime completedAt;
}
