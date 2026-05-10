package com.manga.ai.asset.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.manga.ai.asset.dto.SeriesRoleAssetsVO;
import com.manga.ai.asset.entity.AssetMetadata;
import com.manga.ai.asset.entity.RoleAsset;
import com.manga.ai.asset.mapper.AssetMetadataMapper;
import com.manga.ai.asset.mapper.RoleAssetMapper;
import com.manga.ai.asset.service.AssetService;
import com.manga.ai.common.enums.AssetStatus;
import com.manga.ai.common.enums.ViewType;
import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.common.service.OssService;
import com.manga.ai.role.entity.Role;
import com.manga.ai.role.mapper.RoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
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
    private final RoleMapper roleMapper;
    private final OssService ossService;

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
        newDefaultAsset.setClothingPrompt(currentAsset.getClothingPrompt()); // 复制服装专属提示词
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
            newMetadata.setUserPrompt(sourceMetadata.getUserPrompt());
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
        // 获取所有资产，按服装和版本排序
        LambdaQueryWrapper<RoleAsset> allWrapper = new LambdaQueryWrapper<>();
        allWrapper.eq(RoleAsset::getRoleId, roleId)
                .orderByAsc(RoleAsset::getClothingId)
                .orderByDesc(RoleAsset::getVersion);
        List<RoleAsset> allAssets = roleAssetMapper.selectList(allWrapper);

        log.info("getClothingsByRoleId: roleId={}, 数据库中共有{}条资产记录", roleId, allAssets.size());
        allAssets.forEach(a -> log.info("  资产: id={}, clothingId={}, status={}, isActive={}, clothingName={}",
                a.getId(), a.getClothingId(), a.getStatus(), a.getIsActive(), a.getClothingName()));

        // 按服装分组，收集所有版本
        java.util.Map<Integer, java.util.List<RoleAsset>> clothingVersionsMap = new java.util.LinkedHashMap<>();
        for (RoleAsset asset : allAssets) {
            clothingVersionsMap.computeIfAbsent(asset.getClothingId(), k -> new java.util.ArrayList<>())
                    .add(asset);
        }

        // 合并结果：
        // 1. 优先使用激活版本（包括生成中和失败状态）
        // 2. 如果没有激活版本，使用最新有效版本
        // 3. 如果只有失败版本，也返回它（用户需要看到失败状态并重新生成）
        java.util.List<RoleAsset> result = new java.util.ArrayList<>();
        for (java.util.Map.Entry<Integer, java.util.List<RoleAsset>> entry : clothingVersionsMap.entrySet()) {
            java.util.List<RoleAsset> versions = entry.getValue();

            // 查找激活版本（包括生成中和失败状态）
            RoleAsset activeAsset = null;
            for (RoleAsset asset : versions) {
                if (asset.getIsActive() != null && asset.getIsActive() == 1) {
                    activeAsset = asset;
                    break;
                }
            }

            if (activeAsset != null) {
                // 有激活版本，直接使用
                result.add(activeAsset);
            } else {
                // 没有激活版本，查找最新有效版本（非失败、非生成中）
                RoleAsset validAsset = null;
                for (RoleAsset asset : versions) {
                    if (asset.getStatus() != null
                            && asset.getStatus() != AssetStatus.FAILED.getCode()
                            && asset.getStatus() != AssetStatus.GENERATING.getCode()) {
                        validAsset = asset;
                        break; // 版本已按降序排列，第一个有效的是最新有效版本
                    }
                }

                if (validAsset != null) {
                    result.add(validAsset);
                } else {
                    // 没有有效版本，返回最新版本（包括失败状态，用户需要看到并重新生成）
                    result.add(versions.get(0));
                    log.info("服装只有失败资产，返回最新版本: clothingId={}", entry.getKey());
                }
            }
        }

        log.info("getClothingsByRoleId: roleId={}, 返回{}个服装", roleId, result.size());

        // 填充 detailedView 和 clothingPrompt 字段（来自元数据）
        for (RoleAsset asset : result) {
            LambdaQueryWrapper<AssetMetadata> metadataWrapper = new LambdaQueryWrapper<>();
            metadataWrapper.eq(AssetMetadata::getAssetId, asset.getId())
                    .select(AssetMetadata::getDetailedView, AssetMetadata::getUserPrompt);
            AssetMetadata metadata = assetMetadataMapper.selectOne(metadataWrapper);
            if (metadata != null) {
                asset.setDetailedView(metadata.getDetailedView());
                // 如果 clothingPrompt 为空，从 AssetMetadata 回填
                if ((asset.getClothingPrompt() == null || asset.getClothingPrompt().trim().isEmpty())
                        && metadata.getUserPrompt() != null && !metadata.getUserPrompt().trim().isEmpty()) {
                    asset.setClothingPrompt(metadata.getUserPrompt());
                }
            }
        }

        return result;
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
        // 优先从 RoleAsset.clothingPrompt 获取
        RoleAsset roleAsset = roleAssetMapper.selectById(assetId);
        if (roleAsset != null && roleAsset.getClothingPrompt() != null && !roleAsset.getClothingPrompt().trim().isEmpty()) {
            return roleAsset.getClothingPrompt();
        }

        // 回退到 AssetMetadata
        LambdaQueryWrapper<AssetMetadata> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AssetMetadata::getAssetId, assetId)
                .select(AssetMetadata::getUserPrompt, AssetMetadata::getPrompt);
        AssetMetadata metadata = assetMetadataMapper.selectOne(wrapper);
        if (metadata == null) {
            return null;
        }
        // 优先返回用户原始提示词，如果没有则返回系统构建的提示词
        return metadata.getUserPrompt() != null && !metadata.getUserPrompt().trim().isEmpty()
                ? metadata.getUserPrompt()
                : metadata.getPrompt();
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

        // 不再更新 role.customPrompt，因为每个服装版本的提示词独立存储在 RoleAsset.clothingPrompt 中

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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteClothing(Long roleId, Integer clothingId) {
        // 不允许删除默认服装
        if (clothingId == 1) {
            throw new BusinessException("默认服装不能删除");
        }

        // 删除该服装的所有资产
        LambdaQueryWrapper<RoleAsset> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(RoleAsset::getRoleId, roleId)
                .eq(RoleAsset::getClothingId, clothingId);
        int deleted = roleAssetMapper.delete(deleteWrapper);

        // 不再恢复 role.customPrompt，因为每个服装版本的提示词独立存储在 RoleAsset.clothingPrompt 中
        // 删除非默认服装不影响其他服装的提示词

        log.info("删除服装: roleId={}, clothingId={}, 删除资产数量={}", roleId, clothingId, deleted);
    }

    @Override
    public java.util.Map<Long, List<RoleAsset>> getClothingsBySeriesId(Long seriesId) {
        log.info("开始批量获取系列资产: seriesId={}", seriesId);

        try {
            // 1. 获取该系列所有角色ID
            LambdaQueryWrapper<Role> roleWrapper = new LambdaQueryWrapper<>();
            roleWrapper.eq(Role::getSeriesId, seriesId)
                    .select(Role::getId);
            List<Role> roles = roleMapper.selectList(roleWrapper);

            if (roles.isEmpty()) {
                log.info("系列没有角色: seriesId={}", seriesId);
                return new java.util.HashMap<>();
            }

            List<Long> roleIds = roles.stream()
                    .map(Role::getId)
                    .collect(java.util.stream.Collectors.toList());

            // 2. 批量获取所有角色的资产
            LambdaQueryWrapper<RoleAsset> assetWrapper = new LambdaQueryWrapper<>();
            assetWrapper.in(RoleAsset::getRoleId, roleIds)
                    .orderByAsc(RoleAsset::getRoleId)
                    .orderByAsc(RoleAsset::getClothingId)
                    .orderByDesc(RoleAsset::getVersion);
            List<RoleAsset> allAssets = roleAssetMapper.selectList(assetWrapper);

            log.info("查询到 {} 条资产记录", allAssets.size());

            if (allAssets.isEmpty()) {
                // 返回空 map，但包含所有角色 ID
                java.util.Map<Long, List<RoleAsset>> emptyResult = new java.util.HashMap<>();
                for (Long roleId : roleIds) {
                    emptyResult.put(roleId, new java.util.ArrayList<>());
                }
                return emptyResult;
            }

            // 3. 批量获取所有资产的元数据
            List<Long> assetIds = allAssets.stream()
                    .map(RoleAsset::getId)
                    .collect(java.util.stream.Collectors.toList());

            java.util.Map<Long, AssetMetadata> metadataMap = new java.util.HashMap<>();
            if (!assetIds.isEmpty()) {
                LambdaQueryWrapper<AssetMetadata> metadataWrapper = new LambdaQueryWrapper<>();
                metadataWrapper.in(AssetMetadata::getAssetId, assetIds)
                        .select(AssetMetadata::getAssetId, AssetMetadata::getDetailedView, AssetMetadata::getUserPrompt);
                List<AssetMetadata> metadataList = assetMetadataMapper.selectList(metadataWrapper);
                for (AssetMetadata metadata : metadataList) {
                    metadataMap.put(metadata.getAssetId(), metadata);
                }
            }

            // 4. 按角色分组并处理
            java.util.Map<Long, List<RoleAsset>> result = new java.util.HashMap<>();

            // 先按 roleId + clothingId 分组
            java.util.Map<Long, java.util.Map<Integer, List<RoleAsset>>> roleClothingMap = new java.util.HashMap<>();
            for (RoleAsset asset : allAssets) {
                Long roleId = asset.getRoleId();
                Integer clothingId = asset.getClothingId();
                if (roleId == null || clothingId == null) {
                    continue;
                }
                roleClothingMap
                        .computeIfAbsent(roleId, k -> new java.util.LinkedHashMap<>())
                        .computeIfAbsent(clothingId, k -> new java.util.ArrayList<>())
                        .add(asset);
            }

            // 对每个角色的每个服装，选择合适的版本
            for (java.util.Map.Entry<Long, java.util.Map<Integer, List<RoleAsset>>> roleEntry : roleClothingMap.entrySet()) {
                Long roleId = roleEntry.getKey();
                List<RoleAsset> roleClothings = new java.util.ArrayList<>();

                for (java.util.Map.Entry<Integer, List<RoleAsset>> clothingEntry : roleEntry.getValue().entrySet()) {
                    List<RoleAsset> versions = clothingEntry.getValue();
                    if (versions.isEmpty()) {
                        continue;
                    }

                    // 查找激活版本
                    RoleAsset selectedAsset = null;
                    for (RoleAsset asset : versions) {
                        if (asset.getIsActive() != null && asset.getIsActive() == 1) {
                            selectedAsset = asset;
                            break;
                        }
                    }

                    // 没有激活版本，使用最新有效版本
                    if (selectedAsset == null) {
                        for (RoleAsset asset : versions) {
                            if (asset.getStatus() != null
                                    && asset.getStatus() != AssetStatus.FAILED.getCode()
                                    && asset.getStatus() != AssetStatus.GENERATING.getCode()) {
                                selectedAsset = asset;
                                break;
                            }
                        }
                    }

                    // 还是没有，使用最新版本
                    if (selectedAsset == null && !versions.isEmpty()) {
                        selectedAsset = versions.get(0);
                    }

                    // 填充 detailedView 和 clothingPrompt
                    if (selectedAsset != null) {
                        AssetMetadata metadata = metadataMap.get(selectedAsset.getId());
                        if (metadata != null) {
                            selectedAsset.setDetailedView(metadata.getDetailedView());
                            // 如果 clothingPrompt 为空，从 AssetMetadata 回填
                            if ((selectedAsset.getClothingPrompt() == null || selectedAsset.getClothingPrompt().trim().isEmpty())
                                    && metadata.getUserPrompt() != null && !metadata.getUserPrompt().trim().isEmpty()) {
                                selectedAsset.setClothingPrompt(metadata.getUserPrompt());
                            }
                        }
                        roleClothings.add(selectedAsset);
                    }
                }

                result.put(roleId, roleClothings);
            }

            // 确保所有角色都有条目
            for (Long roleId : roleIds) {
                if (!result.containsKey(roleId)) {
                    result.put(roleId, new java.util.ArrayList<>());
                }
            }

            log.info("getClothingsBySeriesId 完成: seriesId={}, 共{}个角色, {}条资产", seriesId, roleIds.size(), allAssets.size());
            return result;
        } catch (Exception e) {
            log.error("getClothingsBySeriesId 异常: seriesId={}", seriesId, e);
            throw e;
        }
    }

    @Override
    public SeriesRoleAssetsVO getSeriesRoleAssets(Long seriesId) {
        log.info("获取系列角色服装资产: seriesId={}", seriesId);

        SeriesRoleAssetsVO vo = new SeriesRoleAssetsVO();
        List<SeriesRoleAssetsVO.RoleClothingInfo> roleList = new ArrayList<>();

        // 获取该系列所有角色
        LambdaQueryWrapper<Role> roleWrapper = new LambdaQueryWrapper<>();
        roleWrapper.eq(Role::getSeriesId, seriesId)
                .select(Role::getId, Role::getRoleName);
        List<Role> roles = roleMapper.selectList(roleWrapper);

        if (roles.isEmpty()) {
            vo.setRoles(roleList);
            return vo;
        }

        // 批量获取所有角色的服装资产
        java.util.Map<Long, List<RoleAsset>> clothingsMap = getClothingsBySeriesId(seriesId);

        // 构建返回结果
        for (Role role : roles) {
            SeriesRoleAssetsVO.RoleClothingInfo roleInfo = new SeriesRoleAssetsVO.RoleClothingInfo();
            roleInfo.setId(role.getId());
            roleInfo.setRoleName(role.getRoleName());

            List<RoleAsset> clothings = clothingsMap.getOrDefault(role.getId(), new ArrayList<>());
            List<SeriesRoleAssetsVO.ClothingAssetInfo> clothingList = new ArrayList<>();

            for (RoleAsset asset : clothings) {
                SeriesRoleAssetsVO.ClothingAssetInfo clothingInfo = new SeriesRoleAssetsVO.ClothingAssetInfo();
                clothingInfo.setClothingId(asset.getClothingId());
                clothingInfo.setClothingName(asset.getClothingName() != null ? asset.getClothingName() : "服装" + asset.getClothingId());
                clothingInfo.setAssetUrl(ossService.refreshUrl(asset.getFilePath()));
                clothingInfo.setAssetId(asset.getId());
                clothingInfo.setVersion(asset.getVersion());
                clothingInfo.setActive(asset.getIsActive() != null && asset.getIsActive() == 1);
                clothingInfo.setDefaultClothing(asset.getClothingId() != null && asset.getClothingId() == 1);
                clothingList.add(clothingInfo);
            }

            roleInfo.setClothings(clothingList);
            roleList.add(roleInfo);
        }

        vo.setRoles(roleList);
        log.info("获取系列角色服装资产完成: seriesId={}, 角色数={}", seriesId, roleList.size());
        return vo;
    }
}
