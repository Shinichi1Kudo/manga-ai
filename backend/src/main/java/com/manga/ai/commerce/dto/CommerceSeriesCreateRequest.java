package com.manga.ai.commerce.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class CommerceSeriesCreateRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private String name;
    private String category;
    private String visualStyle;
    private String defaultAspectRatio;
    private List<String> enabledContentTypes;
}
