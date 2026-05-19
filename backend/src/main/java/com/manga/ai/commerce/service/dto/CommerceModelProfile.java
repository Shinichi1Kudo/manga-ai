package com.manga.ai.commerce.service.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class CommerceModelProfile implements Serializable {
    private static final long serialVersionUID = 1L;

    private String name;
    private String prompt;
    private String genderAge;
    private String bodyProfile;
    private String styleTags;
    private String speakingPersona;
    private String appearancePrompt;
}
