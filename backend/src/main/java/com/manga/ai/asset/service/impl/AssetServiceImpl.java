package com.manga.ai.asset.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.manga.ai.asset.entity.RoleAsset;
import com.manga.ai.asset.mapper.RoleAssetMapper;
import com.manga.ai.asset.service.AssetService;
import com.manga.ai.common.enums.AssetStatus;
import com.manga.ai.common.enums.ViewType;
import com.manga.ai.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 资产服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetServiceImpl implements AssetService {

    private final RoleAssetMapper roleAssetMapper;

    @Override
    public RoleAsset getAssetById(Long assetId) {
        RoleAsset asset = roleAssetMapper.selectById(assetId);
        if (asset == null) {
            throw new BusinessException("资产不存在");
        }
        return asset;
    }

    @Override
    public List<RoleAsset> getAssetsByRoleId(Long roleId) {
        LambdaQueryWrapper<RoleAsset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RoleAsset::getRoleId, roleId)
                .eq(RoleAsset::getIsActive, 1)
                .orderByAsc(RoleAsset::getViewType);
        return roleAssetMapper.selectList(wrapper);
    }

    @Override
    public RoleAsset getActiveAsset(Long roleId, ViewType viewType, Integer clothingId) {
        LambdaQueryWrapper<RoleAsset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RoleAsset::getRoleId, roleId)
                .eq(RoleAsset::getViewType, viewType.getCode())
                .eq(RoleAsset::getClothingId, clothingId != null ? clothingId : 1)
                .eq(RoleAsset::getIsActive, 1);
        return roleAssetMapper.selectOne(wrapper);
    }

    @Override
    public void updateAssetStatus(Long assetId, Integer status) {
        RoleAsset asset = roleAssetMapper.selectById(assetId);
        if (asset == null) {
            throw new BusinessException("资产不存在");
        }
        asset.setStatus(status);
        roleAssetMapper.updateById(asset);
    }

    @Override
    public void lockAllAssets(Long roleId) {
        LambdaUpdateWrapper<RoleAsset> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(RoleAsset::getRoleId, roleId)
                .eq(RoleAsset::getStatus, AssetStatus.CONFIRMED.getCode())
                .set(RoleAsset::getStatus, AssetStatus.LOCKED.getCode());
        roleAssetMapper.update(null, wrapper);

        log.info("锁定角色所有资产: roleId={}", roleId);
    }

    @Override
    public void setDefaultClothing(Long roleId, Integer clothingId) {
        // 先将该角色所有服装设为非默认
        LambdaUpdateWrapper<RoleAsset> clearWrapper = new LambdaUpdateWrapper<>();
        clearWrapper.eq(RoleAsset::getRoleId, roleId)
                .set(RoleAsset::getIsActive, 0);
        roleAssetMapper.update(null, clearWrapper);

        // 再将指定服装设为默认
        LambdaUpdateWrapper<RoleAsset> setWrapper = new LambdaUpdateWrapper<>();
        setWrapper.eq(RoleAsset::getRoleId, roleId)
                .eq(RoleAsset::getClothingId, clothingId)
                .set(RoleAsset::getIsActive, 1);
        roleAssetMapper.update(null, setWrapper);

        log.info("设置默认服装: roleId={}, clothingId={}", roleId, clothingId);
    }

    @Override
    public Integer getNextClothingId(Long roleId) {
        LambdaQueryWrapper<RoleAsset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RoleAsset::getRoleId, roleId)
                .select(RoleAsset::getClothingId)
                .orderByDesc(RoleAsset::getClothingId)
                .last("LIMIT 1");
        RoleAsset asset = roleAssetMapper.selectOne(wrapper);
        return asset != null ? asset.getClothingId() + 1 : 1;
    }

    @Override
    public List<RoleAsset> getClothingsByRoleId(Long roleId) {
        // 获取所有服装的最新版本（每个服装取最新版本）
        LambdaQueryWrapper<RoleAsset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RoleAsset::getRoleId, roleId)
                .orderByDesc(RoleAsset::getClothingId)
                .orderByDesc(RoleAsset::getVersion);
        List<RoleAsset> allAssets = roleAssetMapper.selectList(wrapper);

        // 按服装分组，取每个服装的最新版本
        java.util.Map<Integer, RoleAsset> clothingMap = new java.util.LinkedHashMap<>();
        for (RoleAsset asset : allAssets) {
            if (!clothingMap.containsKey(asset.getClothingId())) {
                clothingMap.put(asset.getClothingId(), asset);
            }
        }

        return new java.util.ArrayList<>(clothingMap.values());
    }

    @Override
    public List<RoleAsset> getAllAssetsByRoleId(Long roleId) {
        LambdaQueryWrapper<RoleAsset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RoleAsset::getRoleId, roleId)
                .orderByDesc(RoleAsset::getClothingId)
                .orderByDesc(RoleAsset::getVersion);
        return roleAssetMapper.selectList(wrapper);
    }
}
