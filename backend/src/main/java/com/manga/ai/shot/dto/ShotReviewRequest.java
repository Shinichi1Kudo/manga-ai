package com.manga.ai.shot.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 分镜审核请求
 */
@Data
public class ShotReviewRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 是否通过
     */
    private Boolean approved;

    /**
     * 审核意见
     */
    private String comment;
}
