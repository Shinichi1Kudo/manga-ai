package com.manga.ai.role.service;

import com.manga.ai.role.dto.RegenerateRequest;
import com.manga.ai.role.dto.RegenerateResponse;
import com.manga.ai.role.dto.RoleCreateRequest;
import com.manga.ai.role.dto.RoleDetailVO;
import com.manga.ai.role.dto.RoleUpdateRequest;

import java.util.List;

/**
 * 角色服务接口
 */
public interface RoleService {

    /**
     * 创建角色
     */
    Long createRole(RoleCreateRequest request);

    /**
     * 删除角色
     */
    void deleteRole(Long roleId);

    /**
     * 获取角色详情
     */
    RoleDetailVO getRoleDetail(Long roleId);

    /**
     * 获取系列下所有角色
     */
    List<RoleDetailVO> getRolesBySeriesId(Long seriesId);

    /**
     * 更新角色信息
     */
    void updateRole(Long roleId, RoleUpdateRequest request);

    /**
     * 确认角色
     */
    void confirmRole(Long roleId);

    /**
     * 解锁角色（恢复为待审核状态）
     */
    void unlockRole(Long roleId);

    /**
     * 重新生成角色图片
     */
    RegenerateResponse regenerateRoleAssets(Long roleId, RegenerateRequest request);
}
