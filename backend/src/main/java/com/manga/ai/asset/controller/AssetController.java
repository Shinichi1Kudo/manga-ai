package com.manga.ai.asset.controller;

import com.manga.ai.asset.entity.RoleAsset;
import com.manga.ai.asset.service.AssetService;
import com.manga.ai.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.List;

/**
 * 资产控制器
 */
@RestController
@RequestMapping("/v1/assets")
@RequiredArgsConstructor
public class AssetController {

    private final AssetService assetService;

    /**
     * 获取资产详情
     */
    @GetMapping("/{id}")
    public Result<RoleAsset> getAssetById(@PathVariable Long id) {
        RoleAsset result = assetService.getAssetById(id);
        return Result.success(result);
    }

    /**
     * 获取角色的所有资产
     */
    @GetMapping("/role/{roleId}")
    public Result<List<RoleAsset>> getAssetsByRoleId(@PathVariable Long roleId) {
        List<RoleAsset> result = assetService.getAssetsByRoleId(roleId);
        return Result.success(result);
    }

    /**
     * 获取角色的所有服装（每个服装取最新版本）
     */
    @GetMapping("/role/{roleId}/clothings")
    public Result<List<RoleAsset>> getClothingsByRoleId(@PathVariable Long roleId) {
        List<RoleAsset> result = assetService.getClothingsByRoleId(roleId);
        return Result.success(result);
    }

    /**
     * 设置默认服装
     */
    @PostMapping("/role/{roleId}/default/{clothingId}")
    public Result<Void> setDefaultClothing(@PathVariable Long roleId, @PathVariable Integer clothingId) {
        assetService.setDefaultClothing(roleId, clothingId);
        return Result.success();
    }

    /**
     * 获取角色的下一个服装编号
     */
    @GetMapping("/role/{roleId}/next-clothing-id")
    public Result<Integer> getNextClothingId(@PathVariable Long roleId) {
        Integer clothingId = assetService.getNextClothingId(roleId);
        return Result.success(clothingId);
    }

    /**
     * 获取角色的所有资产（包括历史版本）
     */
    @GetMapping("/role/{roleId}/all")
    public Result<List<RoleAsset>> getAllAssetsByRoleId(@PathVariable Long roleId) {
        List<RoleAsset> result = assetService.getAllAssetsByRoleId(roleId);
        return Result.success(result);
    }

    /**
     * 下载资产
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> downloadAsset(@PathVariable Long id) {
        RoleAsset asset = assetService.getAssetById(id);

        // 优先返回透明背景版本
        String filePath = asset.getTransparentPath() != null ?
                asset.getTransparentPath() : asset.getFilePath();

        if (filePath == null) {
            return ResponseEntity.notFound().build();
        }

        File file = new File(filePath);
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + asset.getFileName() + "\"")
                .body(resource);
    }
}
