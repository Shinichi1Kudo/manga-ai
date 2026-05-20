package com.manga.ai.commerce.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class CommerceProjectCreateRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private String contentType;
    private String aspectRatio;
    private String sellingPoints;
    private String targetAudience;
    private String priceOffer;
    private String tryOnRequirements;
    private Boolean tryOnFirstFrameEnabled;
    private String tryOnFirstFrameAspectRatio;
    private String tryOnFirstFrameResolution;
    private List<Long> sceneIds = new ArrayList<>();
}
