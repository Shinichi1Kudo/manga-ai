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
     * 为角色生成新服装资产（基于参考图，异步）
     * @param roleId 角色ID
     * @param referenceImageUrl 参考图片URL
     * @param clothingPrompt 新服装描述
     */
    void generateNewClothingWithReference(Long roleId, String referenceImageUrl, String clothingPrompt);
}
