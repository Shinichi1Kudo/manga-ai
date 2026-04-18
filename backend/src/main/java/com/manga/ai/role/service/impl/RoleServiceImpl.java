package com.manga.ai.role.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.manga.ai.asset.entity.RoleAsset;
import com.manga.ai.asset.mapper.RoleAssetMapper;
import com.manga.ai.common.enums.RoleStatus;
import com.manga.ai.common.exception.AssetLockedException;
import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.common.utils.NamingUtil;
import com.manga.ai.image.service.ImageGenerateService;
import com.manga.ai.role.dto.RegenerateRequest;
import com.manga.ai.role.dto.RoleCreateRequest;
import com.manga.ai.role.dto.RoleDetailVO;
import com.manga.ai.role.dto.RoleUpdateRequest;
import com.manga.ai.role.entity.Role;
import com.manga.ai.role.entity.RoleAttribute;
import com.manga.ai.role.mapper.RoleAttributeMapper;
import com.manga.ai.role.mapper.RoleMapper;
import com.manga.ai.role.service.RoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
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
    @Lazy
    private final ImageGenerateService imageGenerateService;

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
    public List<Long> regenerateRoleAssets(Long roleId, RegenerateRequest request) {
        Role role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException("角色不存在");
        }

        // 检查是否已锁定
        if (RoleStatus.LOCKED.getCode().equals(role.getStatus())) {
            throw new AssetLockedException("角色已锁定，无法重新生成");
        }

        // 如果有修改的提示词，更新角色的自定义提示词
        if (request.getModifiedPrompt() != null && !request.getModifiedPrompt().trim().isEmpty()) {
            role.setCustomPrompt(request.getModifiedPrompt());
            role.setUpdatedAt(LocalDateTime.now());
            roleMapper.updateById(role);
        }

        boolean isNewClothing = Boolean.TRUE.equals(request.getIsNewClothing());

        if (isNewClothing) {
            // 生成新服装 - 使用图生图
            // 获取当前默认服装的图片作为参考
            String referenceImageUrl = getDefaultAssetImageUrl(roleId);
            imageGenerateService.generateNewClothingWithReference(
                    roleId,
                    referenceImageUrl,
                    request.getModifiedPrompt()
            );
        } else {
            // 重新生成当前服装 - 改变角色状态
            role.setStatus(RoleStatus.EXTRACTING.getCode());
            role.setUpdatedAt(LocalDateTime.now());
            roleMapper.updateById(role);
            imageGenerateService.generateCharacterAssets(roleId, request.getClothingId());
        }

        log.info("重新生成角色图片: roleId={}, isNewClothing={}", roleId, isNewClothing);

        return Collections.emptyList();
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
