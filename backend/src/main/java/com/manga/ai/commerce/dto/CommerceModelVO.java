package com.manga.ai.commerce.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class CommerceModelVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long seriesId;
    private String name;
    private String sourceType;
    private String genderAge;
    private String bodyProfile;
    private String styleTags;
    private String speakingPersona;
    private String appearancePrompt;
    private String baseImageUrl;
    private String modelAspectRatio;
    private String status;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
