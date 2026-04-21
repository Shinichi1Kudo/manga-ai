package com.manga.ai.shot.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 更新分镜请求
 */
@Data
public class ShotUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 分镜描述
     */
    private String description;

    /**
     * 时长
     */
    private Integer duration;

    /**
     * 镜头角度
     */
    private String cameraAngle;

    /**
     * 镜头运动
     */
    private String cameraMovement;

    /**
     * 用户修改的提示词
     */
    private String userPrompt;
}
