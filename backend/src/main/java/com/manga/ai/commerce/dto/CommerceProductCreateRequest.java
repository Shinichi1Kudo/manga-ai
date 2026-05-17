package com.manga.ai.commerce.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class CommerceProductCreateRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private String name;
    private String imageUrl;
    private String productType;
    private String sellingPoints;
    private String fabricFitColorSize;
    private String targetAudience;
    private String priceOffer;
    private String tryOnRequirements;
}
