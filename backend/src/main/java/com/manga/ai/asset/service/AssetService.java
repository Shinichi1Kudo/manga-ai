package com.manga.ai.asset.service;

import com.manga.ai.asset.entity.RoleAsset;
import com.manga.ai.common.enums.ViewType;

import java.util.List;

/**
 * 资产服务接口
 */
public interface AssetService {

    /**
     * 获取资产详情
     */
    RoleAsset getAssetById(Long assetId);

    /**
     * 获取角色的所有资产
     */
    List<RoleAsset> getAssetsByRoleId(Long roleId);

    /**
     * 获取角色的指定视图资产
     */
    RoleAsset getActiveAsset(Long roleId, ViewType viewType, Integer clothingId);

    /**
     * 更新资产状态
     */
    void updateAssetStatus(Long assetId, Integer status);

    /**
     * 锁定角色的所有资产
     */
    void lockAllAssets(Long roleId);

    /**
     * 设置默认服装
     * @param roleId 角色ID
     * @param clothingId 服装ID
     */
    void setDefaultClothing(Long roleId, Integer clothingId);

    /**
     * 获取角色的下一个服装编号
     */
    Integer getNextClothingId(Long roleId);

    /**
     * 获取角色的所有服装（按服装分组）
     */
    List<RoleAsset> getClothingsByRoleId(Long roleId);

    /**
     * 获取角色的所有资产（包括历史版本）
     */
    List<RoleAsset> getAllAssetsByRoleId(Long roleId);

    /**
     * 获取资产生成时使用的提示词
     */
    String getAssetPrompt(Long assetId);

    /**
     * 回滚到指定版本的资产
     * @param assetId 目标资产ID
     */
    void rollbackToAsset(Long assetId);

    /**
     * 重命名服装
     * @param roleId 角色ID
     * @param clothingId 服装ID
     * @param clothingName 新名称
     */
    void renameClothing(Long roleId, Integer clothingId, String clothingName);

    /**
     * 删除指定服装（删除该服装的所有版本）
     * @param roleId 角色ID
     * @param clothingId 服装ID
     */
    void deleteClothing(Long roleId, Integer clothingId);
}
