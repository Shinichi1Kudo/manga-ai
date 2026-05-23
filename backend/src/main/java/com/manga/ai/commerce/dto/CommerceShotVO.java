package com.manga.ai.commerce.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class CommerceShotVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long projectId;
    private Integer sortOrder;
    private String title;
    private String visualDescription;
    private String voiceoverText;
    private Integer durationSeconds;
    private String resolution;
    private String videoModel;
    private String videoUrl;
    private String generationStatus;
    private String errorMessage;
}
