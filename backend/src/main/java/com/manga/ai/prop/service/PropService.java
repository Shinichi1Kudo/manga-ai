package com.manga.ai.prop.service;

import com.manga.ai.prop.dto.PropDetailVO;
import java.util.List;

/**
 * 道具服务接口
 */
public interface PropService {

    /**
     * 获取系列下所有道具（含资产）
     * @param seriesId 系列ID
     * @return 道具列表
     */
    List<PropDetailVO> getPropsBySeriesId(Long seriesId);

    /**
     * 获取道具详情（含资产）
     * @param propId 道具ID
     * @return 道具详情
     */
    PropDetailVO getPropDetail(Long propId);

    /**
     * 生成道具资产图片（异步）
     * @param propId 道具ID
     */
    void generatePropAssets(Long propId);

    /**
     * 重新生成道具图片
     * @param propId 道具ID
     * @param customPrompt 自定义提示词（可为null）
     * @param quality 清晰度（可为null，使用原值）
     */
    void regeneratePropAsset(Long propId, String customPrompt, String quality);

    /**
     * 审核道具
     * @param propId 道具ID
     * @param approved 是否通过
     */
    void reviewProp(Long propId, boolean approved);

    /**
     * 锁定道具
     * @param propId 道具ID
     */
    void lockProp(Long propId);

    /**
     * 解锁道具
     * @param propId 道具ID
     */
    void unlockProp(Long propId);

    /**
     * 更新道具名称
     * @param propId 道具ID
     * @param propName 新名称
     */
    void updatePropName(Long propId, String propName);

    /**
     * 删除道具
     * @param propId 道具ID
     */
    void deleteProp(Long propId);

    /**
     * 手动创建道具
     * @param seriesId 系列ID
     * @param episodeId 剧集ID
     * @param propName 道具名称
     * @param quality 清晰度
     * @return 创建的道具ID
     */
    Long createProp(Long seriesId, Long episodeId, String propName, String quality);

    /**
     * 回滚到指定版本
     * @param propId 道具ID
     * @param assetId 要回滚到的资产ID
     */
    void rollbackToVersion(Long propId, Long assetId);
}
