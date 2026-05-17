package com.manga.ai.commerce.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class CommerceSeriesVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String name;
    private String category;
    private String visualStyle;
    private String defaultAspectRatio;
    private List<String> enabledContentTypes;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
