package com.manga.ai.commerce.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class CommerceProductVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long modelId;
    private Long seriesId;
    private String name;
    private String imageUrl;
    private String productType;
    private String sellingPoints;
    private String fabricFitColorSize;
    private String targetAudience;
    private String priceOffer;
    private String tryOnRequirements;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
