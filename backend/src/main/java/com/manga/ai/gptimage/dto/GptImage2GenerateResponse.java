package com.manga.ai.gptimage.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * GPT-Image2 图片生成响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GptImage2GenerateResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String prompt;

    private String aspectRatio;

    private String resolution;

    private String referenceImageUrl;

    private String imageUrl;

    private String status;

    private String statusDesc;

    private Integer progressPercent;

    private String model;

    private String mode;

    private Integer creditCost;

    private String errorMessage;

    private LocalDateTime submittedAt;

    private LocalDateTime completedAt;

    private Integer generationDuration;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public GptImage2GenerateResponse(String imageUrl, String model, String mode) {
        this.imageUrl = imageUrl;
        this.model = model;
        this.mode = mode;
    }
}
