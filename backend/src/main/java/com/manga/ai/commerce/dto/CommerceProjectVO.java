package com.manga.ai.commerce.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class CommerceProjectVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long seriesId;
    private Long modelId;
    private Long productId;
    private String contentType;
    private String aspectRatio;
    private String modelImageUrl;
    private String productImageUrl;
    private String tryOnRequirements;
    private Boolean tryOnFirstFrameEnabled;
    private String tryOnFirstFrameAspectRatio;
    private String tryOnFirstFrameResolution;
    private String tryOnFirstFrameUrl;
    private String tryOnFirstFrameStatus;
    private String tryOnFirstFrameError;
    private Boolean tryOnFirstFrameConfirmed;
    private List<String> sceneImageUrls = new ArrayList<>();
    private String status;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<CommerceShotVO> shots = new ArrayList<>();
}
