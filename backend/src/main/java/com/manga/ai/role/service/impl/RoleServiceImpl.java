package com.manga.ai.role.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.manga.ai.asset.entity.RoleAsset;
import com.manga.ai.asset.mapper.RoleAssetMapper;
import com.manga.ai.asset.service.AssetService;
import com.manga.ai.common.enums.RoleStatus;
import com.manga.ai.common.exception.AssetLockedException;
import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.common.utils.NamingUtil;
import com.manga.ai.image.service.ImageGenerateService;
import com.manga.ai.role.dto.RegenerateRequest;
import com.manga.ai.role.dto.RegenerateResponse;
import com.manga.ai.role.dto.RoleCreateRequest;
import com.manga.ai.role.dto.RoleDetailVO;
import com.manga.ai.role.dto.RoleUpdateRequest;
import com.manga.ai.role.entity.Role;
import com.manga.ai.role.entity.RoleAttribute;
import com.manga.ai.role.mapper.RoleAttributeMapper;
import com.manga.ai.role.mapper.RoleMapper;
import com.manga.ai.role.service.RoleService;
import com.manga.ai.series.entity.Series;
import com.manga.ai.series.mapper.SeriesMapper;
import com.manga.ai.common.enums.SeriesStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 角色服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    private final RoleMapper roleMapper;
    private final RoleAttributeMapper roleAttributeMapper;
    private final RoleAssetMapper roleAssetMapper;
    private final SeriesMapper seriesMapper;
    @Lazy
    private final ImageGenerateService imageGenerateService;
    private final AssetService assetService;
    private final TransactionTemplate transactionTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createRole(RoleCreateRequest request) {
        // 检查系列下是否已有同名角色
        LambdaQueryWrapper<Role> checkWrapper = new LambdaQueryWrapper<>();
        checkWrapper.eq(Role::getSeriesId, request.getSeriesId())
                .eq(Role::getRoleName, request.getRoleName());
        if (roleMapper.selectCount(checkWrapper) > 0) {
            throw new BusinessException("该系列下已存在同名角色");
        }

        // 生成角色编码
        String roleCode = NamingUtil.generateRoleCode(request.getRoleName(), request.getSeriesId());

        // 创建角色 - 状态设为生成中
        Role role = new Role();
        role.setSeriesId(request.getSeriesId());
        role.setRoleName(request.getRoleName());
        role.setRoleCode(roleCode);
        role.setStatus(RoleStatus.EXTRACTING.getCode()); // 生成中
        role.setAge(request.getAge());
        role.setGender(request.getGender());
        role.setAppearance(request.getAppearance());
        role.setPersonality(request.getPersonality());
        role.setClothing(request.getClothing());
        role.setSpecialMarks(request.getSpecialMarks());
        role.setCustomPrompt(request.getCustomPrompt());
        role.setExtractConfidence(new java.math.BigDecimal("1.0"));
        role.setCreatedAt(LocalDateTime.now());
        role.setUpdatedAt(LocalDateTime.now());

        roleMapper.insert(role);
        log.info("创建角色: roleId={}, roleName={}", role.getId(), role.getRoleName());

        // 异步生成图片
        Long roleId = role.getId();
        imageGenerateService.generateCharacterAssets(roleId);

        return roleId;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRole(Long roleId) {
        Role role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException("角色不存在");
        }

        // 检查是否已锁定
        if (RoleStatus.LOCKED.getCode().equals(role.getStatus())) {
            throw new AssetLockedException("角色已锁定，无法删除");
        }

        // 逻辑删除
        roleMapper.deleteById(roleId);
        log.info("删除角色: roleId={}", roleId);
    }

    @Override
    public RoleDetailVO getRoleDetail(Long roleId) {
        Role role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException("角色不存在");
        }

        RoleDetailVO vo = convertToVO(role);

        // 获取扩展属性
        LambdaQueryWrapper<RoleAttribute> attrWrapper = new LambdaQueryWrapper<>();
        attrWrapper.eq(RoleAttribute::getRoleId, roleId);
        List<RoleAttribute> attributes = roleAttributeMapper.selectList(attrWrapper);

        vo.setAttributes(attributes.stream()
                .collect(Collectors.toMap(
                        RoleAttribute::getAttrKey,
                        RoleAttribute::getAttrValue,
                        (v1, v2) -> v1
                )));

        return vo;
    }

    @Override
    public List<RoleDetailVO> getRolesBySeriesId(Long seriesId) {
        LambdaQueryWrapper<Role> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Role::getSeriesId, seriesId)
                .orderByAsc(Role::getRoleCode);
        List<Role> roles = roleMapper.selectList(wrapper);

        // 添加日志，打印查询到的角色提示词
        roles.forEach(role -> log.info("查询角色: roleId={}, customPrompt={}", role.getId(), role.getCustomPrompt()));

        return roles.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateRole(Long roleId, RoleUpdateRequest request) {
        Role role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException("角色不存在");
        }

        // 检查是否已锁定
        if (RoleStatus.LOCKED.getCode().equals(role.getStatus())) {
            throw new AssetLockedException("角色已锁定，无法修改");
        }

        // 更新角色信息
        if (request.getRoleName() != null) {
            role.setRoleName(request.getRoleName());
        }
        if (request.getAge() != null) {
            role.setAge(request.getAge());
        }
        if (request.getGender() != null) {
            role.setGender(request.getGender());
        }
        if (request.getAppearance() != null) {
            role.setAppearance(request.getAppearance());
        }
        if (request.getPersonality() != null) {
            role.setPersonality(request.getPersonality());
        }
        if (request.getClothing() != null) {
            role.setClothing(request.getClothing());
        }
        if (request.getSpecialMarks() != null) {
            role.setSpecialMarks(request.getSpecialMarks());
        }
        if (request.getCustomPrompt() != null) {
            role.setCustomPrompt(request.getCustomPrompt());
        }

        role.setUpdatedAt(LocalDateTime.now());
        roleMapper.updateById(role);

        log.info("更新角色信息: roleId={}", roleId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmRole(Long roleId) {
        Role role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException("角色不存在");
        }

        // 检查是否已锁定
        if (RoleStatus.LOCKED.getCode().equals(role.getStatus())) {
            throw new AssetLockedException("角色已锁定");
        }

        // 更新状态为已确认
        role.setStatus(RoleStatus.CONFIRMED.getCode());
        role.setUpdatedAt(LocalDateTime.now());
        roleMapper.updateById(role);

        log.info("确认角色: roleId={}", roleId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unlockRole(Long roleId) {
        Role role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException("角色不存在");
        }

        // 检查是否是已确认或已锁定状态（只有这两种状态可以解锁）
        if (!RoleStatus.CONFIRMED.getCode().equals(role.getStatus())
                && !RoleStatus.LOCKED.getCode().equals(role.getStatus())) {
            throw new BusinessException("当前状态不支持解锁操作");
        }

        // 更新角色状态为待审核
        role.setStatus(RoleStatus.PENDING_REVIEW.getCode());
        role.setUpdatedAt(LocalDateTime.now());
        roleMapper.updateById(role);

        // 同时更新系列状态为待审核
        Series series = seriesMapper.selectById(role.getSeriesId());
        if (series != null && SeriesStatus.LOCKED.getCode().equals(series.getStatus())) {
            series.setStatus(SeriesStatus.PENDING_REVIEW.getCode());
            series.setUpdatedAt(LocalDateTime.now());
            seriesMapper.updateById(series);
            log.info("解锁角色同时更新系列状态: roleId={}, seriesId={}", roleId, role.getSeriesId());
        }

        log.info("解锁角色: roleId={}", roleId);
    }

    @Override
    public RegenerateResponse regenerateRoleAssets(Long roleId, RegenerateRequest request) {
        Role role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException("角色不存在");
        }

        // 检查是否已锁定
        if (RoleStatus.LOCKED.getCode().equals(role.getStatus())) {
            throw new AssetLockedException("角色已锁定，无法重新生成");
        }

        boolean isNewClothing = Boolean.TRUE.equals(request.getIsNewClothing());
        String referenceImageUrl = request.getReferenceImageUrl();

        // 判断是否有有效的参考图
        boolean hasReferenceImage = referenceImageUrl != null && !referenceImageUrl.trim().isEmpty();

        // 详细日志：打印请求参数
        log.info("========== 重新生成请求 ==========");
        log.info("roleId={}, roleName={}", roleId, role.getRoleName());
        log.info("isNewClothing={}, hasReferenceImage={}", isNewClothing, hasReferenceImage);
        log.info("referenceImageUrl={}", referenceImageUrl);
        log.info("clothingId={}, clothingName={}", request.getClothingId(), request.getClothingName());
        log.info("modifiedPrompt={}", request.getModifiedPrompt());
        log.info("=================================");

        Integer clothingId = request.getClothingId();
        if (clothingId == null || clothingId <= 0) {
            clothingId = 1;
        }

        // 使用 TransactionTemplate 确保角色提示词更新和资产创建在同一个事务中
        // 这样在返回响应后，新标签页的查询能看到一致的数据
        final Integer finalClothingId = clothingId;
        final String clothingName = request.getClothingName();
        final String modifiedPrompt = request.getModifiedPrompt();

        Object[] result = transactionTemplate.execute(status -> {
            // 1. 先更新角色的自定义提示词（如果有修改）
            if (modifiedPrompt != null && !modifiedPrompt.trim().isEmpty()) {
                Role roleToUpdate = roleMapper.selectById(roleId);
                if (roleToUpdate != null) {
                    roleToUpdate.setCustomPrompt(modifiedPrompt);
                    roleToUpdate.setUpdatedAt(LocalDateTime.now());
                    roleMapper.updateById(roleToUpdate);
                    log.info("更新角色提示词: roleId={}, prompt={}", roleId, modifiedPrompt);
                }
            }

            // 2. 创建生成中的资产
            Integer nextClothingId = finalClothingId;
            Integer nextVersion = null;
            Long generatingAssetId = null;
            Long previousActiveAssetId = null;

            if (isNewClothing) {
                // 新服装 - 创建新的clothingId
                nextClothingId = assetService.getNextClothingId(roleId);
                nextVersion = 1;
                long[] assetResult = imageGenerateService.createGeneratingAsset(roleId, nextClothingId, clothingName);
                generatingAssetId = assetResult[0];
                previousActiveAssetId = assetResult[1] > 0 ? assetResult[1] : null;
            } else {
                // 重新生成现有服装 - 在指定服装上生成新版本
                nextVersion = imageGenerateService.getNextVersion(roleId, nextClothingId);
                long[] assetResult = imageGenerateService.createGeneratingAsset(roleId, nextClothingId, null);
                generatingAssetId = assetResult[0];
                previousActiveAssetId = assetResult[1] > 0 ? assetResult[1] : null;
            }

            return new Object[]{nextClothingId, nextVersion, generatingAssetId, previousActiveAssetId};
        });

        // 解析事务结果
        clothingId = (Integer) result[0];
        Integer nextVersion = (Integer) result[1];
        Long generatingAssetId = (Long) result[2];
        Long previousActiveAssetId = (Long) result[3];

        log.info("资产创建事务已提交: roleId={}, clothingId={}, assetId={}", roleId, clothingId, generatingAssetId);

        // 启动异步生成（在事务提交后）
        // 只要有参考图就使用图生图，不管是新服装还是重新生成
        if (hasReferenceImage) {
            log.info("使用图生图模式: referenceImageUrl={}", referenceImageUrl);
            imageGenerateService.generateNewClothingWithReference(
                    roleId,
                    clothingId,
                    generatingAssetId,
                    previousActiveAssetId,
                    referenceImageUrl,
                    modifiedPrompt,
                    clothingName
            );
        } else {
            log.info("使用文生图模式");
            imageGenerateService.generateCharacterAssets(roleId, clothingId, generatingAssetId, previousActiveAssetId);
        }

        log.info("重新生成角色图片: roleId={}, clothingId={}, version={}, isNewClothing={}",
                roleId, clothingId, nextVersion, isNewClothing);

        // 返回详细的响应信息
        return RegenerateResponse.builder()
                .clothingId(clothingId)
                .version(nextVersion)
                .assetId(generatingAssetId)
                .build();
    }

    /**
     * 获取角色默认资产图片URL
     */
    private String getDefaultAssetImageUrl(Long roleId) {
        LambdaQueryWrapper<com.manga.ai.asset.entity.RoleAsset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(com.manga.ai.asset.entity.RoleAsset::getRoleId, roleId)
                .eq(com.manga.ai.asset.entity.RoleAsset::getIsActive, 1)
                .last("LIMIT 1");
        com.manga.ai.asset.entity.RoleAsset asset = roleAssetMapper.selectOne(wrapper);
        return asset != null ? asset.getFilePath() : null;
    }

    private RoleDetailVO convertToVO(Role role) {
        RoleDetailVO vo = new RoleDetailVO();
        BeanUtils.copyProperties(role, vo);
        return vo;
    }
}
