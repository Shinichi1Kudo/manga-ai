package com.manga.ai.image.service;

import com.manga.ai.image.dto.ImageGenerateRequest;
import com.manga.ai.image.dto.ImageGenerateResponse;

/**
 * 图像生成服务接口
 */
public interface ImageGenerateService {

    /**
     * 生成角色三视图图片（文生图）
     * @param request 生成请求
     * @return 生成结果
     */
    ImageGenerateResponse generateCharacterSheet(ImageGenerateRequest request);

    /**
     * 基于参考图生成新服装（图生图）
     * @param request 生成请求（包含参考图URL）
     * @return 生成结果
     */
    ImageGenerateResponse generateCharacterSheetWithReference(ImageGenerateRequest request);

    /**
     * 为角色生成三视图资产（异步）
     */
    void generateCharacterAssets(Long roleId);

    /**
     * 为角色生成指定服装的三视图资产（异步）
     * @param roleId 角色ID
     * @param clothingId 服装ID，为null时自动分配新ID
     */
    void generateCharacterAssets(Long roleId, Integer clothingId);

    /**
     * 为角色生成指定服装的三视图资产（异步，使用已创建的生成中资产）
     * @param roleId 角色ID
     * @param clothingId 服装ID
     * @param generatingAssetId 已创建的生成中资产ID
     */
    void generateCharacterAssets(Long roleId, Integer clothingId, Long generatingAssetId);

    /**
     * 为角色生成指定服装的三视图资产（异步，使用已创建的生成中资产和之前激活的资产ID）
     * @param roleId 角色ID
     * @param clothingId 服装ID
     * @param generatingAssetId 已创建的生成中资产ID
     * @param previousActiveAssetId 之前激活的资产ID（用于失败时恢复）
     */
    void generateCharacterAssets(Long roleId, Integer clothingId, Long generatingAssetId, Long previousActiveAssetId);

    /**
     * 为角色生成指定服装的三视图资产（异步，指定图片比例和清晰度）
     * @param roleId 角色ID
     * @param clothingId 服装ID
     * @param generatingAssetId 已创建的生成中资产ID
     * @param previousActiveAssetId 之前激活的资产ID（用于失败时恢复）
     * @param aspectRatio 图片比例
     * @param quality 清晰度
     */
    void generateCharacterAssets(Long roleId, Integer clothingId, Long generatingAssetId, Long previousActiveAssetId, String aspectRatio, String quality);

    /**
     * 为角色生成指定服装的三视图资产（异步，指定图片比例、清晰度和风格）
     * @param roleId 角色ID
     * @param clothingId 服装ID
     * @param generatingAssetId 已创建的生成中资产ID
     * @param previousActiveAssetId 之前激活的资产ID（用于失败时恢复）
     * @param aspectRatio 图片比例
     * @param quality 清晰度
     * @param styleKeywords 风格关键词
     */
    void generateCharacterAssets(Long roleId, Integer clothingId, Long generatingAssetId, Long previousActiveAssetId, String aspectRatio, String quality, String styleKeywords);

    /**
     * 为角色生成指定服装的三视图资产（异步，指定图片比例、清晰度、风格和原始提示词）
     * @param roleId 角色ID
     * @param clothingId 服装ID
     * @param generatingAssetId 已创建的生成中资产ID
     * @param previousActiveAssetId 之前激活的资产ID（用于失败时恢复）
     * @param aspectRatio 图片比例
     * @param quality 清晰度
     * @param styleKeywords 风格关键词
     * @param originalPrompt 用户原始提示词（不包含系统附加的模板）
     */
    void generateCharacterAssets(Long roleId, Integer clothingId, Long generatingAssetId, Long previousActiveAssetId, String aspectRatio, String quality, String styleKeywords, String originalPrompt);

    /**
     * 为角色生成指定服装的三视图资产（异步，指定图片比例、清晰度、风格、原始提示词和精细三视图模式）
     * @param roleId 角色ID
     * @param clothingId 服装ID
     * @param generatingAssetId 已创建的生成中资产ID
     * @param previousActiveAssetId 之前激活的资产ID（用于失败时恢复）
     * @param aspectRatio 图片比例
     * @param quality 清晰度
     * @param styleKeywords 风格关键词
     * @param originalPrompt 用户原始提示词（不包含系统附加的模板）
     * @param detailedView 是否使用精细三视图模式生成
     */
    void generateCharacterAssets(Long roleId, Integer clothingId, Long generatingAssetId, Long previousActiveAssetId, String aspectRatio, String quality, String styleKeywords, String originalPrompt, Boolean detailedView);

    /**
     * 为角色生成指定服装的三视图资产（异步，指定图片比例、清晰度、风格、原始提示词、精细三视图模式和大头特写布局）
     * @param roleId 角色ID
     * @param clothingId 服装ID
     * @param generatingAssetId 已创建的生成中资产ID
     * @param previousActiveAssetId 之前激活的资产ID（用于失败时恢复）
     * @param aspectRatio 图片比例
     * @param quality 清晰度
     * @param styleKeywords 风格关键词
     * @param originalPrompt 用户原始提示词（不包含系统附加的模板）
     * @param detailedView 是否使用精细三视图模式生成
     * @param faceCloseupView 是否使用大头特写+三视图布局
     */
    void generateCharacterAssets(Long roleId, Integer clothingId, Long generatingAssetId, Long previousActiveAssetId, String aspectRatio, String quality, String styleKeywords, String originalPrompt, Boolean detailedView, Boolean faceCloseupView);

    /**
     * 为角色生成新服装资产（基于参考图，异步）
     * @param roleId 角色ID
     * @param clothingId 服装ID
     * @param generatingAssetId 已创建的生成中资产ID
     * @param previousActiveAssetId 之前激活的资产ID（用于失败时恢复）
     * @param referenceImageUrl 参考图片URL
     * @param clothingPrompt 新服装描述
     * @param clothingName 服装名称
     */
    void generateNewClothingWithReference(Long roleId, Integer clothingId, Long generatingAssetId, Long previousActiveAssetId, String referenceImageUrl, String clothingPrompt, String clothingName);

    /**
     * 为角色生成新服装资产（基于参考图，异步，指定图片比例和清晰度）
     * @param roleId 角色ID
     * @param clothingId 服装ID
     * @param generatingAssetId 已创建的生成中资产ID
     * @param previousActiveAssetId 之前激活的资产ID（用于失败时恢复）
     * @param referenceImageUrl 参考图片URL
     * @param clothingPrompt 新服装描述
     * @param clothingName 服装名称
     * @param aspectRatio 图片比例
     * @param quality 清晰度
     */
    void generateNewClothingWithReference(Long roleId, Integer clothingId, Long generatingAssetId, Long previousActiveAssetId, String referenceImageUrl, String clothingPrompt, String clothingName, String aspectRatio, String quality);

    /**
     * 为角色生成新服装资产（基于参考图，异步，指定图片比例、清晰度和风格）
     * @param roleId 角色ID
     * @param clothingId 服装ID
     * @param generatingAssetId 已创建的生成中资产ID
     * @param previousActiveAssetId 之前激活的资产ID（用于失败时恢复）
     * @param referenceImageUrl 参考图片URL
     * @param clothingPrompt 新服装描述
     * @param clothingName 服装名称
     * @param aspectRatio 图片比例
     * @param quality 清晰度
     * @param styleKeywords 风格关键词
     */
    void generateNewClothingWithReference(Long roleId, Integer clothingId, Long generatingAssetId, Long previousActiveAssetId, String referenceImageUrl, String clothingPrompt, String clothingName, String aspectRatio, String quality, String styleKeywords);

    /**
     * 为角色生成新服装资产（基于参考图，异步，指定图片比例、清晰度、风格和原始提示词）
     * @param roleId 角色ID
     * @param clothingId 服装ID
     * @param generatingAssetId 已创建的生成中资产ID
     * @param previousActiveAssetId 之前激活的资产ID（用于失败时恢复）
     * @param referenceImageUrl 参考图片URL
     * @param clothingPrompt 新服装描述
     * @param clothingName 服装名称
     * @param aspectRatio 图片比例
     * @param quality 清晰度
     * @param styleKeywords 风格关键词
     * @param originalPrompt 用户原始提示词（不包含系统附加的模板）
     */
    void generateNewClothingWithReference(Long roleId, Integer clothingId, Long generatingAssetId, Long previousActiveAssetId, String referenceImageUrl, String clothingPrompt, String clothingName, String aspectRatio, String quality, String styleKeywords, String originalPrompt);

    /**
     * 为角色生成新服装资产（基于参考图，异步，指定图片比例、清晰度、风格、原始提示词和精细三视图模式）
     * @param roleId 角色ID
     * @param clothingId 服装ID
     * @param generatingAssetId 已创建的生成中资产ID
     * @param previousActiveAssetId 之前激活的资产ID（用于失败时恢复）
     * @param referenceImageUrl 参考图片URL
     * @param clothingPrompt 新服装描述
     * @param clothingName 服装名称
     * @param aspectRatio 图片比例
     * @param quality 清晰度
     * @param styleKeywords 风格关键词
     * @param originalPrompt 用户原始提示词（不包含系统附加的模板）
     * @param detailedView 是否使用精细三视图模式生成
     */
    void generateNewClothingWithReference(Long roleId, Integer clothingId, Long generatingAssetId, Long previousActiveAssetId, String referenceImageUrl, String clothingPrompt, String clothingName, String aspectRatio, String quality, String styleKeywords, String originalPrompt, Boolean detailedView);

    /**
     * 创建生成中的资产记录（同步）
     * @param roleId 角色ID
     * @param clothingId 服装ID
     * @param clothingName 服装名称（可为null）
     * @return [assetId, previousActiveAssetId] 之前激活的资产ID可能为0
     */
    long[] createGeneratingAsset(Long roleId, Integer clothingId, String clothingName);

    /**
     * 获取下一个版本号
     * @param roleId 角色ID
     * @param clothingId 服装ID
     * @return 下一个版本号
     */
    Integer getNextVersion(Long roleId, Integer clothingId);

    /**
     * 生成场景背景图（文生图）
     * @param request 生成请求（customPrompt为场景描述）
     * @return 生成结果
     */
    ImageGenerateResponse generateSceneImage(ImageGenerateRequest request);

    /**
     * 生成道具产品图（文生图）
     * @param request 生成请求（customPrompt为道具描述）
     * @return 生成结果
     */
    ImageGenerateResponse generatePropImage(ImageGenerateRequest request);
}
