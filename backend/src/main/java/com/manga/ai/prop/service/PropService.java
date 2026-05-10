package com.manga.ai.prop.service;

import com.manga.ai.prop.dto.PropDetailVO;
import org.springframework.web.multipart.MultipartFile;
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
     * 获取系列下当前剧集可见的道具（已锁定全系列可见，未锁定仅来源剧集可见）
     * @param seriesId 系列ID
     * @param episodeId 当前剧集ID，可为null
     * @return 道具列表
     */
    List<PropDetailVO> getPropsBySeriesId(Long seriesId, Long episodeId);

    /**
     * 获取道具详情（含资产）
     * @param propId 道具ID
     * @return 道具详情
     */
    PropDetailVO getPropDetail(Long propId);

    /**
     * 获取当前剧集视角下的道具详情（用于轮询/历史版本展示）
     * @param propId 道具ID
     * @param episodeId 当前剧集ID，可为null
     * @return 道具详情
     */
    PropDetailVO getPropDetail(Long propId, Long episodeId);

    /**
     * 获取当前剧集视角下的道具详情，可选择带回历史版本。
     * @param propId 道具ID
     * @param episodeId 当前剧集ID，可为null
     * @param includeHistory 是否包含可回滚历史版本
     * @return 道具详情
     */
    PropDetailVO getPropDetail(Long propId, Long episodeId, boolean includeHistory);

    /**
     * 生成道具资产图片（异步）
     * @param propId 道具ID
     */
    void generatePropAssets(Long propId);

    /**
     * 生成道具资产（含积分扣费）
     * 同步扣费后异步生成
     * @param propId 道具ID
     * @param userId 用户ID（可为null，为null时从上下文获取）
     */
    void generatePropAssetsWithCredit(Long propId, Long userId);

    /**
     * 生成道具资产（含积分扣费），并记录来源剧集
     * @param propId 道具ID
     * @param userId 用户ID（可为null，为null时从上下文获取）
     * @param episodeId 来源剧集ID，可为null
     */
    void generatePropAssetsWithCredit(Long propId, Long userId, Long episodeId);

    /**
     * 重新生成道具图片
     * @param propId 道具ID
     * @param customPrompt 自定义提示词（可为null）
     * @param quality 清晰度（可为null，使用原值）
     */
    void regeneratePropAsset(Long propId, String customPrompt, String quality);

    /**
     * 重新生成道具图片（含积分扣费）
     * 同步扣费后异步生成
     * @param propId 道具ID
     * @param customPrompt 自定义提示词（可为null）
     * @param quality 清晰度（可为null，使用原值）
     * @param userId 用户ID（可为null，为null时从上下文获取）
     */
    void regeneratePropAssetWithCredit(Long propId, String customPrompt, String quality, Long userId);

    /**
     * 重新生成道具图片（含积分扣费），并记录来源剧集
     * @param propId 道具ID
     * @param customPrompt 自定义提示词（可为null）
     * @param quality 清晰度（可为null，使用原值）
     * @param userId 用户ID（可为null，为null时从上下文获取）
     * @param episodeId 来源剧集ID，可为null
     */
    void regeneratePropAssetWithCredit(Long propId, String customPrompt, String quality, Long userId, Long episodeId);

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
     * 锁定道具，并优先把当前剧集生成的版本设为共享版本
     * @param propId 道具ID
     * @param episodeId 当前剧集ID，可为null
     */
    void lockProp(Long propId, Long episodeId);

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
     * @param customPrompt 自定义提示词（可选）
     * @return 创建的道具ID
     */
    Long createProp(Long seriesId, Long episodeId, String propName, String quality, String customPrompt);

    /**
     * 手动上传道具图片并创建/更新道具资产
     * @param seriesId 系列ID
     * @param episodeId 剧集ID
     * @param propName 道具名称
     * @param quality 清晰度
     * @param customPrompt 自定义提示词（可选）
     * @param file 用户上传的1:1图片
     * @return 道具详情
     */
    PropDetailVO uploadPropAsset(Long seriesId, Long episodeId, String propName, String quality, String customPrompt, MultipartFile file);

    /**
     * 为已有道具手动上传图片资产
     * @param propId 道具ID
     * @param episodeId 剧集ID
     * @param customPrompt 自定义提示词（可选）
     * @param file 用户上传的1:1图片
     * @return 道具详情
     */
    PropDetailVO uploadPropAsset(Long propId, Long episodeId, String customPrompt, MultipartFile file);

    /**
     * 回滚到指定版本
     * @param propId 道具ID
     * @param assetId 要回滚到的资产ID
     */
    void rollbackToVersion(Long propId, Long assetId);

    /**
     * 回滚到指定版本，并绑定当前剧集上下文。
     * @param propId 道具ID
     * @param assetId 要回滚到的资产ID
     * @param episodeId 当前剧集ID，可为null
     */
    void rollbackToVersion(Long propId, Long assetId, Long episodeId);
}
