package com.manga.ai.scene.service;

import com.manga.ai.scene.dto.SceneDetailVO;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

/**
 * 场景服务接口
 */
public interface SceneService {

    /**
     * 获取系列下所有场景（含资产）
     * @param seriesId 系列ID
     * @return 场景列表
     */
    List<SceneDetailVO> getScenesBySeriesId(Long seriesId);

    /**
     * 获取场景详情（含资产）
     * @param sceneId 场景ID
     * @return 场景详情
     */
    SceneDetailVO getSceneDetail(Long sceneId);

    /**
     * 生成场景资产图片（异步）
     * @param sceneId 场景ID
     */
    void generateSceneAssets(Long sceneId);

    /**
     * 生成场景资产（含积分扣费）
     * 同步扣费后异步生成
     * @param sceneId 场景ID
     * @param userId 用户ID（可为null，为null时从上下文获取）
     */
    void generateSceneAssetsWithCredit(Long sceneId, Long userId);

    /**
     * 重新生成场景图片
     * @param sceneId 场景ID
     * @param customPrompt 自定义提示词（可为null）
     * @param aspectRatio 图片比例（可为null，使用原值）
     * @param quality 清晰度（可为null，使用原值）
     */
    void regenerateSceneAsset(Long sceneId, String customPrompt, String aspectRatio, String quality);

    /**
     * 重新生成场景图片（含积分扣费）
     * 同步扣费后异步生成
     * @param sceneId 场景ID
     * @param customPrompt 自定义提示词（可为null）
     * @param aspectRatio 图片比例（可为null，使用原值）
     * @param quality 清晰度（可为null，使用原值）
     * @param userId 用户ID（可为null，为null时从上下文获取）
     */
    void regenerateSceneAssetWithCredit(Long sceneId, String customPrompt, String aspectRatio, String quality, Long userId);

    /**
     * 审核场景
     * @param sceneId 场景ID
     * @param approved 是否通过
     */
    void reviewScene(Long sceneId, boolean approved);

    /**
     * 锁定场景
     * @param sceneId 场景ID
     */
    void lockScene(Long sceneId);

    /**
     * 解锁场景
     * @param sceneId 场景ID
     */
    void unlockScene(Long sceneId);

    /**
     * 更新场景名称
     * @param sceneId 场景ID
     * @param sceneName 新名称
     */
    void updateSceneName(Long sceneId, String sceneName);

    /**
     * 删除场景
     * @param sceneId 场景ID
     */
    void deleteScene(Long sceneId);

    /**
     * 手动创建场景
     * @param seriesId 系列ID
     * @param episodeId 剧集ID
     * @param sceneName 场景名称
     * @param aspectRatio 图片比例
     * @param quality 清晰度
     * @param customPrompt 自定义提示词（可选）
     * @return 创建的场景ID
     */
    Long createScene(Long seriesId, Long episodeId, String sceneName, String aspectRatio, String quality, String customPrompt);

    /**
     * 手动上传场景图片并创建/更新场景资产
     * @param seriesId 系列ID
     * @param episodeId 剧集ID
     * @param sceneName 场景名称
     * @param aspectRatio 图片比例
     * @param quality 清晰度
     * @param customPrompt 自定义说明
     * @param file 用户上传并裁剪后的图片
     * @return 场景详情
     */
    SceneDetailVO uploadSceneAsset(Long seriesId, Long episodeId, String sceneName, String aspectRatio, String quality, String customPrompt, MultipartFile file);

    /**
     * 为已有场景手动上传图片资产
     * @param sceneId 场景ID
     * @param aspectRatio 图片比例
     * @param customPrompt 自定义说明
     * @param file 用户上传并裁剪后的图片
     * @return 场景详情
     */
    SceneDetailVO uploadSceneAsset(Long sceneId, String aspectRatio, String customPrompt, MultipartFile file);

    /**
     * 回滚到指定版本
     * @param sceneId 场景ID
     * @param assetId 要回滚到的资产ID
     */
    void rollbackToVersion(Long sceneId, Long assetId);

    /**
     * 重置卡在生成中状态的场景
     * @param sceneId 场景ID
     */
    void resetStuckStatus(Long sceneId);
}
