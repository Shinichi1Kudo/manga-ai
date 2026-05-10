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
     * 分镜描述（剧情）
     */
    private String description;

    /**
     * 剧情是否由用户主动编辑
     */
    private Boolean descriptionEdited;

    /**
     * 开始时间（秒）
     */
    private Integer startTime;

    /**
     * 结束时间（秒）
     */
    private Integer endTime;

    /**
     * 时长
     */
    private Integer duration;

    /**
     * 视频分辨率: 480p, 720p
     */
    private String resolution;

    /**
     * 视频比例: 16:9, 4:3, 1:1, 3:4, 9:16, 21:9
     */
    private String aspectRatio;

    /**
     * 镜头类型（中景/特写/全景等）
     */
    private String shotType;

    /**
     * 镜头角度
     */
    private String cameraAngle;

    /**
     * 镜头运动
     */
    private String cameraMovement;

    /**
     * 音效描述
     */
    private String soundEffect;

    /**
     * 分镜名称
     */
    private String shotName;

    /**
     * 场景名称
     */
    private String sceneName;

    /**
     * 场景是否由用户主动编辑
     */
    private Boolean sceneEdited;

    /**
     * 用户修改的提示词
     */
    private String userPrompt;

    /**
     * 生成状态: 0-待生成 1-生成中 2-已完成 3-失败
     */
    private Integer generationStatus;

    /**
     * 视频生成模型: seedance-2.0-fast, seedance-2.0
     */
    private String videoModel;
}
