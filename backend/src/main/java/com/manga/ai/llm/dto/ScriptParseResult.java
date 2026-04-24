package com.manga.ai.llm.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 剧本解析结果
 */
@Data
public class ScriptParseResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 解析状态
     */
    private String status;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 场景列表
     */
    private List<SceneInfo> scenes;

    /**
     * 分镜列表
     */
    private List<ShotInfo> shots;

    /**
     * 道具列表
     */
    private List<PropInfo> props;

    /**
     * 场景信息
     */
    @Data
    public static class SceneInfo implements Serializable {
        private String sceneName;
        private String sceneCode;
        private String description;
        private String locationType;  // 室内/室外
        private String timeOfDay;     // 白天/夜晚/黄昏
        private String weather;
    }

    /**
     * 分镜信息
     */
    @Data
    public static class ShotInfo implements Serializable {
        private Integer shotNumber;
        private String sceneCode;
        private String description;      // 剧情
        private Integer startTime;       // 开始时间（秒）
        private Integer endTime;         // 结束时间（秒）
        private Integer duration;        // 时长(秒)，向后兼容
        private String shotType;         // 镜头类型：中景/特写/全景 + 推镜头等
        private String cameraAngle;      // 镜头角度，向后兼容
        private String cameraMovement;   // 镜头运动，向后兼容
        private String soundEffect;      // 音效描述
        private List<CharacterInShot> characters;
        private List<PropInShot> props;
    }

    /**
     * 分镜中的角色
     */
    @Data
    public static class CharacterInShot implements Serializable {
        private String roleName;
        private String action;
        private String expression;
        private Integer clothingId;
    }

    /**
     * 分镜中的道具
     */
    @Data
    public static class PropInShot implements Serializable {
        private String propName;
        private String position;  // 位置描述
    }

    /**
     * 道具信息
     */
    @Data
    public static class PropInfo implements Serializable {
        private String propName;
        private String propCode;
        private String description;
        private String propType;
        private String color;
    }
}
