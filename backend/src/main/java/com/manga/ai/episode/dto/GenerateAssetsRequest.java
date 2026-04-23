package com.manga.ai.episode.dto;

import lombok.Data;
import java.io.Serializable;
import java.util.List;

/**
 * 批量生成资产请求
 */
@Data
public class GenerateAssetsRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 需要生成图片的场景ID列表（已存在的）
     */
    private List<Long> sceneIds;

    /**
     * 需要生成图片的道具ID列表（已存在的）
     */
    private List<Long> propIds;

    /**
     * 需要创建并生成图片的新场景名称列表
     */
    private List<String> newSceneNames;

    /**
     * 需要创建并生成图片的新道具名称列表
     */
    private List<String> newPropNames;

    /**
     * 未选中的已存在场景ID列表（需要删除）
     */
    private List<Long> unselectedSceneIds;

    /**
     * 未选中的已存在道具ID列表（需要删除）
     */
    private List<Long> unselectedPropIds;

    /**
     * 未选中的新场景名称列表（不需要创建）
     */
    private List<String> unselectedSceneNames;

    /**
     * 未选中的新道具名称列表（不需要创建）
     */
    private List<String> unselectedPropNames;

    /**
     * 可选：统一设置清晰度
     */
    private String quality;
}
