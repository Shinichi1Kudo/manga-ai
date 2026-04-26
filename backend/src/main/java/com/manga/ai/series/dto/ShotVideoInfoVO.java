package com.manga.ai.series.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 分镜视频信息VO
 */
@Data
public class ShotVideoInfoVO {

    private Long shotId;

    private Integer shotNumber;

    private String shotName;

    private String description;

    private Integer duration;

    private String videoUrl;

    private String thumbnailUrl;

    private Integer generationStatus;

    private LocalDateTime createdAt;
}
