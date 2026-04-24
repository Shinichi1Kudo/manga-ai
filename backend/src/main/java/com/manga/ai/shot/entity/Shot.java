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
     * 场景ID
     */
    private Long sceneId;

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
}
