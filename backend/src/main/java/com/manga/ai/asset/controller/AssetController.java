package com.manga.ai.asset.controller;

import com.manga.ai.asset.dto.SeriesRoleAssetsVO;
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
import java.util.Map;

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
     * 获取资产生成时使用的提示词
     */
    @GetMapping("/{id}/prompt")
    public Result<String> getAssetPrompt(@PathVariable Long id) {
        String prompt = assetService.getAssetPrompt(id);
        return Result.success(prompt);
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
     * 批量获取系列所有角色的服装资产
     */
    @GetMapping("/series/{seriesId}/clothings")
    public Result<Map<Long, List<RoleAsset>>> getClothingsBySeriesId(@PathVariable Long seriesId) {
        Map<Long, List<RoleAsset>> result = assetService.getClothingsBySeriesId(seriesId);
        return Result.success(result);
    }

    /**
     * 获取系列所有角色的服装资产（包含角色名称，用于前端@提及）
     */
    @GetMapping("/series/{seriesId}/role-assets")
    public Result<SeriesRoleAssetsVO> getSeriesRoleAssets(@PathVariable Long seriesId) {
        SeriesRoleAssetsVO result = assetService.getSeriesRoleAssets(seriesId);
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
     * 回滚到指定版本的资产
     */
    @PostMapping("/{assetId}/rollback")
    public Result<Void> rollbackAsset(@PathVariable Long assetId) {
        assetService.rollbackToAsset(assetId);
        return Result.success();
    }

    /**
     * 重命名服装
     */
    @PutMapping("/role/{roleId}/clothing/{clothingId}/name")
    public Result<Void> renameClothing(@PathVariable Long roleId, @PathVariable Integer clothingId,
                                        @RequestBody java.util.Map<String, String> request) {
        String clothingName = request.get("clothingName");
        assetService.renameClothing(roleId, clothingId, clothingName);
        return Result.success();
    }

    /**
     * 删除服装（删除该服装的所有版本）
     */
    @DeleteMapping("/role/{roleId}/clothing/{clothingId}")
    public Result<Void> deleteClothing(@PathVariable Long roleId, @PathVariable Integer clothingId) {
        assetService.deleteClothing(roleId, clothingId);
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
