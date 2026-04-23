package com.manga.ai.episode.dto;

import lombok.Data;
import java.io.Serializable;
import java.util.List;

/**
 * 解析后的资产清单VO
 * 用于让用户选择需要生成图片的资产
 */
@Data
public class ParsedAssetsVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long episodeId;
    private List<SceneAssetInfo> scenes;
    private List<PropAssetInfo> props;

    /**
     * 场景资产信息
     */
    @Data
    public static class SceneAssetInfo implements Serializable {
        private static final long serialVersionUID = 1L;

        private Long id;              // 场景ID
        private String sceneName;
        private String sceneCode;
        private String description;
        private Boolean isNew;        // 是否新识别的
        private Boolean isLocked;     // 是否已锁定
        private Boolean hasAssets;    // 是否已有资产生成过
        private String previewUrl;    // 预览图（已存在资产则显示）
        private Boolean selected;     // 默认选中状态
    }

    /**
     * 道具资产信息
     */
    @Data
    public static class PropAssetInfo implements Serializable {
        private static final long serialVersionUID = 1L;

        private Long id;              // 道具ID
        private String propName;
        private String propCode;
        private String description;
        private Boolean isNew;        // 是否新识别的
        private Boolean isLocked;     // 是否已锁定
        private Boolean hasAssets;    // 是否已有资产生成过
        private String previewUrl;    // 预览图（已存在资产则显示）
        private Boolean selected;     // 默认选中状态
    }
}
