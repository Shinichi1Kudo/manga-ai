package com.manga.ai.commerce.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class CommerceSceneVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long seriesId;
    private String name;
    private String sceneType;
    private String libraryType;
    private String prompt;
    private String imageUrl;
    private String aspectRatio;
    private String resolution;
    private String status;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
