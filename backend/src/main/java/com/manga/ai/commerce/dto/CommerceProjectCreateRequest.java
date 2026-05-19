package com.manga.ai.commerce.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class CommerceProjectCreateRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private String contentType;
    private String aspectRatio;
    private String sellingPoints;
    private String targetAudience;
    private String priceOffer;
    private String tryOnRequirements;
}
