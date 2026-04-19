package com.manga.ai.role.controller;

import com.manga.ai.common.result.Result;
import com.manga.ai.role.dto.RegenerateRequest;
import com.manga.ai.role.dto.RegenerateResponse;
import com.manga.ai.role.dto.RoleCreateRequest;
import com.manga.ai.role.dto.RoleDetailVO;
import com.manga.ai.role.dto.RoleUpdateRequest;
import com.manga.ai.role.service.RoleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 角色控制器
 */
@RestController
@RequestMapping("/v1/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    /**
     * 创建角色
     */
    @PostMapping
    public Result<Long> createRole(@Valid @RequestBody RoleCreateRequest request) {
        Long roleId = roleService.createRole(request);
        return Result.success(roleId);
    }

    /**
     * 删除角色
     */
    @DeleteMapping("/{id}")
    public Result<Void> deleteRole(@PathVariable Long id) {
        roleService.deleteRole(id);
        return Result.success();
    }

    /**
     * 获取角色详情
     */
    @GetMapping("/{id}")
    public Result<RoleDetailVO> getRoleDetail(@PathVariable Long id) {
        RoleDetailVO result = roleService.getRoleDetail(id);
        return Result.success(result);
    }

    /**
     * 获取系列下所有角色
     */
    @GetMapping("/series/{seriesId}")
    public Result<List<RoleDetailVO>> getRolesBySeriesId(@PathVariable Long seriesId) {
        List<RoleDetailVO> result = roleService.getRolesBySeriesId(seriesId);
        return Result.success(result);
    }

    /**
     * 更新角色信息
     */
    @PutMapping("/{id}")
    public Result<Void> updateRole(@PathVariable Long id, @Valid @RequestBody RoleUpdateRequest request) {
        roleService.updateRole(id, request);
        return Result.success();
    }

    /**
     * 确认角色
     */
    @PostMapping("/{id}/confirm")
    public Result<Void> confirmRole(@PathVariable Long id) {
        roleService.confirmRole(id);
        return Result.success();
    }

    /**
     * 重新生成角色图片
     */
    @PostMapping("/{id}/regenerate")
    public Result<RegenerateResponse> regenerateRoleAssets(
            @PathVariable Long id,
            @Valid @RequestBody RegenerateRequest request) {
        RegenerateResponse result = roleService.regenerateRoleAssets(id, request);
        return Result.success(result);
    }
}
