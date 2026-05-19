package com.manga.ai.commerce.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class CommerceModelCreateRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private String name;
    private String sourceType;
    private String gender;
    private String modelType;
    private String ethnicity;
    private String modelAspectRatio;
    private String genderAge;
    private String bodyProfile;
    private String styleTags;
    private String speakingPersona;
    private String appearancePrompt;
    private String baseImageUrl;
}
