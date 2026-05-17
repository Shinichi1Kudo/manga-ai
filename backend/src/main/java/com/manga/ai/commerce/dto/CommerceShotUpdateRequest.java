package com.manga.ai.commerce.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class CommerceShotUpdateRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private String visualDescription;
    private String voiceoverText;
}
