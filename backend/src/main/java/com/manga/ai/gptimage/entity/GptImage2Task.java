package com.manga.ai.gptimage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * GPT-Image2 图片生成任务
 */
@Data
@TableName("gpt_image2_task")
public class GptImage2Task implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String prompt;

    private String aspectRatio;

    private String resolution;

    private String referenceImageUrl;

    private String imageUrl;

    /**
     * pending / running / succeeded / failed
     */
    private String status;

    private String model;

    private String mode;

    private Integer creditCost;

    private Boolean creditsRefunded;

    private String errorMessage;

    private LocalDateTime submittedAt;

    private LocalDateTime completedAt;

    private Integer generationDuration;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
