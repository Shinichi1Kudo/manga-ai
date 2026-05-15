package com.manga.ai.shot.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 分镜实体
 */
@Data
@TableName("shot")
public class Shot implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 剧集ID
     */
    private Long episodeId;

    /**
     * 分镜编号
     */
    private Integer shotNumber;

    /**
     * 分镜名称（用户自定义，如"开场"、"追逐戏"）
     */
    private String shotName;

    /**
     * 场景ID
     */
    private Long sceneId;

    /**
     * 场景名称（LLM识别的场景描述）
     */
    private String sceneName;

    /**
     * 分镜描述
     */
    private String description;

    /**
     * 镜头角度
     */
    private String cameraAngle;

    /**
     * 镜头运动
     */
    private String cameraMovement;

    /**
     * 镜头类型（中景/特写/全景 + 推镜头等）
     */
    private String shotType;

    /**
     * 开始时间（秒）
     */
    private Integer startTime;

    /**
     * 结束时间（秒）
     */
    private Integer endTime;

    /**
     * 时长(秒), 最大15秒
     */
    private Integer duration;

    /**
     * 视频分辨率: 480p, 720p
     */
    private String resolution;

    /**
     * 视频生成模型: seedance-2.0-fast (Seedance 2.0 Fast VIP), seedance-2.0 (Seedance 2.0 VIP), kling-v3-omni
     */
    private String videoModel;

    /**
     * 视频比例: 16:9, 4:3, 1:1, 3:4, 9:16, 21:9
     */
    private String aspectRatio;

    /**
     * 音效描述
     */
    private String soundEffect;

    /**
     * 角色信息JSON
     */
    private String charactersJson;

    /**
     * 道具信息JSON
     */
    private String propsJson;

    /**
     * 参考提示词(系统生成)
     */
    private String referencePrompt;

    /**
     * 用户修改的提示词
     */
    private String userPrompt;

    /**
     * 视频URL
     */
    private String videoUrl;

    /**
     * 缩略图URL
     */
    private String thumbnailUrl;

    /**
     * 视频生成Seed
     */
    private Long videoSeed;

    /**
     * 生成状态: 0-待生成 1-生成中 2-已完成 3-失败
     */
    private Integer generationStatus;

    /**
     * 生成失败原因
     */
    private String generationError;

    /**
     * 生成耗时（秒）
     */
    private Integer generationDuration;

    /**
     * 生成开始时间
     */
    private LocalDateTime generationStartTime;

    /**
     * 视频生成扣除的积分（用于失败时返还）
     */
    private Integer deductedCredits;

    /**
     * 审核状态: 0-待审核 1-已通过 2-已拒绝
     */
    private Integer status;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 是否删除
     */
    @TableLogic
    private Integer isDeleted;

    /**
     * 剧情是否用户编辑过
     */
    private Boolean descriptionEdited;

    /**
     * 场景是否用户编辑过
     */
    private Boolean sceneEdited;
}
