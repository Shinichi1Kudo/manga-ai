package com.manga.ai.asset.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.manga.ai.asset.entity.AssetMetadata;
import com.manga.ai.asset.entity.RoleAsset;
import com.manga.ai.asset.mapper.AssetMetadataMapper;
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
    private final AssetMetadataMapper assetMetadataMapper;

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
        // 如果已经是默认服装，不做处理
        if (clothingId == 1) {
            log.info("已经是默认服装，无需设置: roleId={}", roleId);
            return;
        }

        // 获取当前服装的最新版本（按版本号降序取第一条）
        LambdaQueryWrapper<RoleAsset> currentWrapper = new LambdaQueryWrapper<>();
        currentWrapper.eq(RoleAsset::getRoleId, roleId)
                .eq(RoleAsset::getClothingId, clothingId)
                .orderByDesc(RoleAsset::getVersion)
                .last("LIMIT 1");
        RoleAsset currentAsset = roleAssetMapper.selectOne(currentWrapper);

        if (currentAsset == null) {
            throw new BusinessException("当前服装没有资产");
        }

        // 获取默认服装的最新版本号
        LambdaQueryWrapper<RoleAsset> defaultWrapper = new LambdaQueryWrapper<>();
        defaultWrapper.eq(RoleAsset::getRoleId, roleId)
                .eq(RoleAsset::getClothingId, 1)
                .orderByDesc(RoleAsset::getVersion)
                .last("LIMIT 1");
        RoleAsset latestDefault = roleAssetMapper.selectOne(defaultWrapper);
        int newVersion = (latestDefault != null) ? latestDefault.getVersion() + 1 : 1;

        // 将原默认服装的所有版本设为非激活
        LambdaUpdateWrapper<RoleAsset> deactivateWrapper = new LambdaUpdateWrapper<>();
        deactivateWrapper.eq(RoleAsset::getRoleId, roleId)
                .eq(RoleAsset::getClothingId, 1)
                .set(RoleAsset::getIsActive, 0);
        roleAssetMapper.update(null, deactivateWrapper);

        // 复制当前资产到默认服装（创建新版本）
        RoleAsset newDefaultAsset = new RoleAsset();
        newDefaultAsset.setRoleId(roleId);
        newDefaultAsset.setAssetType(currentAsset.getAssetType());
        newDefaultAsset.setViewType(currentAsset.getViewType());
        newDefaultAsset.setClothingId(1);  // 设为默认服装
        newDefaultAsset.setClothingName("默认");
        newDefaultAsset.setVersion(newVersion);
        newDefaultAsset.setFileName(currentAsset.getFileName());
        newDefaultAsset.setFilePath(currentAsset.getFilePath());
        newDefaultAsset.setThumbnailPath(currentAsset.getThumbnailPath());
        newDefaultAsset.setTransparentPath(currentAsset.getTransparentPath());
        newDefaultAsset.setStatus(currentAsset.getStatus());
        newDefaultAsset.setIsActive(1);  // 设为激活
        newDefaultAsset.setValidationPassed(currentAsset.getValidationPassed());
        newDefaultAsset.setCreatedAt(java.time.LocalDateTime.now());
        newDefaultAsset.setUpdatedAt(java.time.LocalDateTime.now());

        roleAssetMapper.insert(newDefaultAsset);

        // 复制元数据
        LambdaQueryWrapper<AssetMetadata> metadataWrapper = new LambdaQueryWrapper<>();
        metadataWrapper.eq(AssetMetadata::getAssetId, currentAsset.getId());
        AssetMetadata sourceMetadata = assetMetadataMapper.selectOne(metadataWrapper);

        if (sourceMetadata != null) {
            AssetMetadata newMetadata = new AssetMetadata();
            newMetadata.setAssetId(newDefaultAsset.getId());
            newMetadata.setPrompt(sourceMetadata.getPrompt());
            newMetadata.setNegativePrompt(sourceMetadata.getNegativePrompt());
            newMetadata.setSeed(sourceMetadata.getSeed());
            newMetadata.setModelVersion(sourceMetadata.getModelVersion());
            newMetadata.setImageWidth(sourceMetadata.getImageWidth());
            newMetadata.setImageHeight(sourceMetadata.getImageHeight());
            newMetadata.setAspectRatio(sourceMetadata.getAspectRatio());
            newMetadata.setGenerationTimeMs(sourceMetadata.getGenerationTimeMs());
            newMetadata.setCreatedAt(java.time.LocalDateTime.now());
            assetMetadataMapper.insert(newMetadata);
        }

        log.info("设置默认服装: roleId={}, 从clothingId={}复制到默认服装, 新版本={}", roleId, clothingId, newVersion);
    }

    @Override
    public Integer getNextClothingId(Long roleId) {
        // 使用 selectList + LIMIT 1 避免多行结果问题
        LambdaQueryWrapper<RoleAsset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RoleAsset::getRoleId, roleId)
                .select(RoleAsset::getClothingId)
                .orderByDesc(RoleAsset::getClothingId)
                .last("LIMIT 1");
        List<RoleAsset> assets = roleAssetMapper.selectList(wrapper);

        int maxClothingId = 0;
        if (!assets.isEmpty()) {
            maxClothingId = assets.get(0).getClothingId();
        }

        int nextId = maxClothingId + 1;
        log.info("getNextClothingId: roleId={}, maxClothingId={}, nextId={}",
                roleId, maxClothingId, nextId);
        return nextId;
    }

    @Override
    public List<RoleAsset> getClothingsByRoleId(Long roleId) {
        // 获取所有服装的最新版本（每个服装取最新版本）
        LambdaQueryWrapper<RoleAsset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RoleAsset::getRoleId, roleId)
                .orderByAsc(RoleAsset::getClothingId)  // 按 clothingId 升序，确保 clothingId=1 在前面
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

    @Override
    public String getAssetPrompt(Long assetId) {
        LambdaQueryWrapper<AssetMetadata> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AssetMetadata::getAssetId, assetId)
                .select(AssetMetadata::getPrompt);
        AssetMetadata metadata = assetMetadataMapper.selectOne(wrapper);
        return metadata != null ? metadata.getPrompt() : null;
    }

    @Override
    public void rollbackToAsset(Long assetId) {
        // 获取目标资产
        RoleAsset targetAsset = roleAssetMapper.selectById(assetId);
        if (targetAsset == null) {
            throw new BusinessException("资产不存在");
        }

        Long roleId = targetAsset.getRoleId();
        Integer clothingId = targetAsset.getClothingId();

        // 将该服装的所有版本设为非激活
        LambdaUpdateWrapper<RoleAsset> deactivateWrapper = new LambdaUpdateWrapper<>();
        deactivateWrapper.eq(RoleAsset::getRoleId, roleId)
                .eq(RoleAsset::getClothingId, clothingId)
                .set(RoleAsset::getIsActive, 0);
        roleAssetMapper.update(null, deactivateWrapper);

        // 将目标版本设为激活
        targetAsset.setIsActive(1);
        roleAssetMapper.updateById(targetAsset);

        log.info("回滚资产: assetId={}, roleId={}, clothingId={}, version={}",
                assetId, roleId, clothingId, targetAsset.getVersion());
    }

    @Override
    public void renameClothing(Long roleId, Integer clothingId, String clothingName) {
        // 不允许修改默认服装的名称
        if (clothingId == 1) {
            throw new BusinessException("默认服装不支持修改名称");
        }

        // 更新该服装所有版本的名称
        LambdaUpdateWrapper<RoleAsset> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(RoleAsset::getRoleId, roleId)
                .eq(RoleAsset::getClothingId, clothingId)
                .set(RoleAsset::getClothingName, clothingName);
        roleAssetMapper.update(null, updateWrapper);

        log.info("重命名服装: roleId={}, clothingId={}, newName={}", roleId, clothingId, clothingName);
    }
}
